package com.dwinovo.numen.core.autonomy;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.perception.Reflexes;
import com.dwinovo.numen.core.task.CompanionTickDispatcher;
import com.dwinovo.numen.entity.CompanionLifecycle;
import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * <strong>Idle-autonomy layer L3 (Wave A)</strong> — "没人管它时,身体自己过日子。"
 * The spinal-cord habit loop that sits between the reflexes and the brain
 * (see {@code docs/idle-autonomy-L3.md}). It runs with <em>zero LLM / zero token</em>:
 * all behaviour is a state machine reusing the existing navigation muscle
 * ({@link PlayerNav}) and attention brain ({@code LookBrain}, which ticks itself —
 * this layer never touches gaze, only locomotion). Wave A scope: scheduler skeleton +
 * tethered stroll + opportunistic pickup + dream-journal. B/C/D are not implemented here.
 *
 * <h2>Arbitration (§3 constitution — the load-bearing part)</h2>
 * Lowest priority of all: <em>reflex &gt; owner task &gt; brain turn in flight &gt; idle autonomy</em>.
 * Every tick the gate ({@link AutonomyLogic#shouldYield}) checks three things and stands down if ANY holds:
 * <ul>
 *   <li><b>reflex owns the body</b> — {@link Reflexes#ownsBody} (survival closed-loop v2);</li>
 *   <li><b>an owner task is active</b> — a pending tool call
 *       ({@link CompanionTickDispatcher#queueFor}{@code .hasPending()}); the RUNNING phase of a task lives in
 *       the dispatcher's private map and is not server-observable through the public API, so it is covered by
 *       (a) the quiet-settle window below — any pending call resets the clock — and (b) the optional
 *       {@code ownerTaskActive} hint on {@link #tick(NumenPlayer, boolean)} for a caller that can see it;</li>
 *   <li><b>the brain has a turn in flight</b> — not directly observable server-side (the agent loop runs on the
 *       owner's client), so it is bridged by the same quiet-settle window: the brain keeps queuing tool calls,
 *       each refreshing {@code lastBusyTick}, so autonomy only engages after a genuine idle gap.</li>
 *   <li><b>control authority is not BUILTIN</b> (W5) — {@code ControlRegistry.effective(companion) != BUILTIN}.
 *       This layer IS the built-in brain's own lowest-priority limb, so it must yield whenever an external
 *       brain holds the body (EXTERNAL) or nothing is designated to drive it (NONE) — otherwise an external
 *       driver that thinks for minutes between calls gets back a body that sleepwalked off on its own.</li>
 * </ul>
 * When it yields it abandons any unfinished idle work immediately — <em>no cleanup debt</em> (§3).
 *
 * <h2>Rhythm (§3 — 灵动≠忙碌)</h2>
 * Mostly {@code REST} (standing while {@code LookBrain} glances around); occasionally a short {@code STROLL}
 * leg; opportunistically a {@code PICKUP}. An enforced 5–10 s cooldown sits between actions. One action at a
 * time; liveliness, not busyness.
 *
 * <h2>Tick mounting</h2>
 * Self-contained: driven by an external call to {@link #tick(NumenPlayer)}. It self-registers its lifecycle
 * cleanup lazily on the first call, so if the mount line is never added it is inert dead code that touches
 * nothing. <strong>The main controller must add one line to the per-companion loop of
 * {@code CompanionTickDispatcher.tick} — {@code AutonomyScheduler.tick(ap);} — after the task tick / higher
 * priorities.</strong> With that line the layer works; without it, nothing happens.
 *
 * <p>Server-thread only (driven from the server tick dispatcher), exactly like {@link Reflexes} — the static
 * per-companion {@link #STATES} map needs no synchronisation.
 */
public final class AutonomyScheduler {

    // ---- §2 master-locked constants (settings-panel dials come later; Wave A hard-codes them) ----
    /** Tether radius while the owner anchors (online, same dimension). TODO(config): 3rd knob of the ability matrix. */
    static final int ONLINE_TETHER_RADIUS = 24;
    /** Tether radius around the home anchor (owner offline / another dimension). TODO(config): settings panel. */
    static final int OFFLINE_TETHER_RADIUS = 8;

    // ---- rhythm / cadence ----
    /** Quiet ticks required since the last higher-activity tick before autonomy engages (bridges brain think→act). */
    /** Idle gap required before autonomy engages. The brain thinks CLIENT-side, so the
     *  server can't see "a turn is in flight" — during a mission a slow model (30–90 s per
     *  think) leaves the body apparently unemployed between tool calls, and with a 3 s
     *  settle the sleepwalk kept wandering off MID-MISSION (field: the dragon-run playtest).
     *  Two minutes covers any sane think gap; only a genuinely dismissed companion strolls. */
    private static final int IDLE_SETTLE_TICKS = 2400;      // 2 min — post-work hold ("下班缓冲")
    private static final long REST_MIN_TICKS = 100;          // 5 s between actions
    private static final long REST_MAX_TICKS = 200;          // 10 s between actions
    private static final long STROLL_TIMEOUT_TICKS = 200;    // give up a wander leg after 10 s
    private static final long PICKUP_TIMEOUT_TICKS = 200;    // give up an approach after 10 s
    private static final long JOURNAL_MAX_SESSION_TICKS = 2400;   // flush a running session at most every ~2 min
    /** Minimum banked wander time before an interrupt may flush a journal line (~60 s).
     *  Below this the session suspends and carries forward instead — no three-second diary. */
    private static final long JOURNAL_MIN_SUBSTANCE_TICKS = 1200;

    // ---- stroll / pickup geometry ----
    private static final int PICKUP_SCAN_RADIUS = 8;
    private static final int STROLL_MIN_LEG = 3;             // a leg is at least this many blocks
    private static final double ARRIVE_RADIUS = 1.75;        // horizontal "close enough" for a stroll target
    private static final double PICKUP_REACH = 1.3;          // vanilla auto-absorbs within ~1 block
    private static final int UNDERGROUND_MARGIN = 4;         // >this below the surface ⇒ wander on the body's own level
    /**
     * Locomotion speed handed to {@link PlayerNav}. The executor treats {@code speed} only as a sprint gate
     * ({@code speed >= 1.0} ⇒ sprint), NOT as a velocity scalar, so anything {@code < 1.0} is a calm vanilla
     * walk — exactly what a stroll wants ("慢走,不跑").
     */
    private static final double WALK_SPEED = 0.6;

    private enum Phase { REST, STROLL, PICKUP }

    private static final Map<UUID, State> STATES = new LinkedHashMap<>();
    private static final Random RANDOM = new Random();
    private static boolean registered;   // server-thread only, like Reflexes#registered

    /** Per-companion idle-life state (episode-scoped; dropped on body removal). */
    private static final class State {
        Phase phase = Phase.REST;
        long restUntil;             // REST: tick the rest ends
        long actionDeadline;        // STROLL/PICKUP: give-up tick
        long lastBusyTick;          // last tick a higher-priority activity was seen

        PlayerNav nav;              // the active stroll/pickup navigation (null while resting)
        double targetX, targetZ;    // stroll target centre (for the arrival predicate)
        double legStartX, legStartZ;// where the current leg began (for the distance tally)

        ItemEntity pickupTarget;    // the drop we're walking to
        ItemStack pickupStack;      // its snapshot, for the journal if we absorb it

        /**
         * A stable home point for the offline anchor. The engine's home landmark ({@code LandmarkStore}) lives
         * on the owner's CLIENT and is unreachable from this server tick, so per the master ruling we fall back
         * to the NPC's own point — captured once, lazily, so the offline tether is a fixed 8-block disc.
         */
        BlockPos homeAnchor;

        // ---- dream-journal session accumulator (batched summary, not per-action) ----
        boolean sessionActive;
        long sessionStart;
        /** Active-wander ticks carried over from insubstantial interrupted sessions (a slow
         *  brain wakes every 30–90 s and interrupts the stroll; without carrying, every gap
         *  produced its own "漫步约 3 秒" journal line — field-observed noise). */
        long carriedTicks;
        int strollLegs;
        double strollBlocks;
        final Map<String, Integer> picked = new LinkedHashMap<>();
        final Map<String, Integer> noticedUnpicked = new LinkedHashMap<>();   // seen while backpack-full

        State(long now) { this.restUntil = now; this.lastBusyTick = now; }
    }

    private AutonomyScheduler() {}

    /** The tether anchor + its radius for a given tick. */
    private record Anchor(double x, double z, int radius) {}

    // ============================================================ entry points

    /**
     * Drive one tick of idle autonomy for {@code body}. THE mount point — add
     * {@code AutonomyScheduler.tick(ap);} to {@code CompanionTickDispatcher.tick}'s per-companion loop.
     * Self-contained; no owner-task hint (falls back to the quiet-settle window for running tasks).
     */
    public static void tick(NumenPlayer body) {
        tick(body, false);
    }

    /**
     * As {@link #tick(NumenPlayer)}, with an extra {@code ownerTaskActive} busy hint OR-ed into the gate.
     * A caller that CAN observe the dispatcher's running task (e.g. a future
     * {@code CompanionTickDispatcher.isBusy(uuid)}) should pass it here to close the arbitration gate on a
     * long-running task precisely; passing {@code false} means "no extra signal" (the default single-arg path).
     */
    public static void tick(NumenPlayer body, boolean ownerTaskActive) {
        ensureRegistered();
        if (body == null || body.isDeadOrDying()) return;
        if (!(body.level() instanceof ServerLevel level)) return;
        if (body.gameMode() == GameType.CREATIVE) return;   // creative wandering is left to a later (creative) wave

        UUID id = body.getUUID();
        long now = level.getGameTime();
        State s = STATES.computeIfAbsent(id, k -> new State(now));

        // ===== arbitration gate (§3): reflex > owner task > brain-turn > control-authority > idle autonomy =====
        boolean reflexOwns = Reflexes.ownsBody(body);
        boolean pending = CompanionTickDispatcher.queueFor(id).hasPending();
        // Control-authority term (W5): the sleepwalk layer is the BUILT-IN brain's own lowest limb, so it
        // must stand down whenever some other brain holds the body (EXTERNAL) or nobody is designated to
        // drive it at all (NONE) — otherwise an external agent that perceives/thinks for minutes between
        // calls comes back to a body that strolled off on its own, narrated as an autonomy journal event
        // the external side has no way to read. Computed fresh every tick (cheap HashMap lookup), same as
        // reflexOwns/pending above — control can change hands between one tick and the next.
        boolean notBuiltin = com.dwinovo.numen.entity.ControlRegistry.effective(id)
                != com.dwinovo.numen.entity.ControlRegistry.ControlState.BUILTIN;
        if (AutonomyLogic.shouldYield(reflexOwns, ownerTaskActive, pending, notBuiltin)) {
            onBusy(body, s, now);   // abandon any unfinished leg (no cleanup debt), close/flush the session
            return;
        }
        // Quiet-settle: engage only after a genuine idle gap (bridges the brain's think→act turns).
        if (!AutonomyLogic.settled(now, s.lastBusyTick, IDLE_SETTLE_TICKS)) return;

        // ===== fully idle → run the spinal idle-life state machine =====
        switch (s.phase) {
            case REST -> tickRest(body, level, s, now);
            case STROLL -> tickStroll(body, s, now);
            case PICKUP -> tickPickup(body, s, now);
        }

        // Cap a very long continuous session so the journal batches at a sane cadence.
        maybeFlushJournal(body, s, now, false);
    }

    // ============================================================ busy handling

    /** A higher priority claimed the body: drop the unfinished idle work (no cleanup debt) and settle the journal. */
    private static void onBusy(NumenPlayer body, State s, long now) {
        s.lastBusyTick = now;
        if (s.nav != null) { s.nav.stop(); s.nav = null; }   // one halt at most (usually the task hasn't started driving yet)
        s.pickupTarget = null;
        s.pickupStack = null;
        s.phase = Phase.REST;
        s.restUntil = now;
        maybeFlushJournal(body, s, now, true);   // interrupted → summarise what got done (or drop an empty session)
    }

    // ============================================================ REST → decide next action

    private static void tickRest(NumenPlayer body, ServerLevel level, State s, long now) {
        if (!AutonomyLogic.restElapsed(now, s.restUntil)) return;   // still standing; LookBrain glances around
        if (tryStartPickup(body, level, s, now)) return;            // opportunistic pickup first
        startStroll(body, level, s, now);                          // else a short wander leg
    }

    // ============================================================ opportunistic pickup

    /**
     * Scan a small radius for a ground drop and, if the backpack has a free slot, start walking to the nearest
     * one (vanilla auto-absorbs on contact). Backpack full ⇒ never pick up (master ruling: 一切归背包,满则停),
     * only note the miss for the journal. Containers are never touched (this only sees {@link ItemEntity}s).
     */
    private static boolean tryStartPickup(NumenPlayer body, ServerLevel level, State s, long now) {
        AABB box = body.getBoundingBox().inflate(PICKUP_SCAN_RADIUS);
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, box);
        ItemEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (ItemEntity e : drops) {
            if (!e.isAlive() || e.isRemoved()) continue;
            double d = body.distanceToSqr(e);
            if (d < best) { best = d; nearest = e; }
        }
        if (nearest == null) return false;

        if (body.getInventory().getFreeSlot() < 0) {   // no empty slot → backpack full: stop picking up
            noteUnpicked(s, nearest);
            return false;
        }

        ensureSession(s, now);
        s.pickupTarget = nearest;
        s.pickupStack = nearest.getItem().copy();
        final ItemEntity target = nearest;
        s.nav = new PlayerNav(body, target.blockPosition(), WALK_SPEED,
                () -> target.isRemoved() || !target.isAlive() || body.closerThan(target, PICKUP_REACH));
        s.phase = Phase.PICKUP;
        s.actionDeadline = now + PICKUP_TIMEOUT_TICKS;
        return true;
    }

    private static void tickPickup(NumenPlayer body, State s, long now) {
        if (s.nav == null || s.pickupTarget == null) { enterRest(s, now); return; }
        boolean gone = s.pickupTarget.isRemoved() || !s.pickupTarget.isAlive();
        boolean timedOut = now >= s.actionDeadline;
        PlayerNav.Status st = s.nav.tick();
        if (st == PlayerNav.Status.RUNNING && !gone && !timedOut) return;
        // Ended. The drop vanishing while we were right on top of it means vanilla absorbed it → journal it.
        if (gone && s.pickupStack != null) recordPicked(s, s.pickupStack);
        s.nav.stop();
        s.nav = null;
        s.pickupTarget = null;
        s.pickupStack = null;
        enterRest(s, now);
    }

    // ============================================================ tethered stroll

    private static void startStroll(NumenPlayer body, ServerLevel level, State s, long now) {
        Anchor a = anchor(body, s);
        double ang = RANDOM.nextDouble() * Math.PI * 2.0;
        double dist = STROLL_MIN_LEG + RANDOM.nextDouble() * Math.max(0.0, a.radius() - STROLL_MIN_LEG);
        double tx = a.x() + Math.cos(ang) * dist;
        double tz = a.z() + Math.sin(ang) * dist;
        double[] c = AutonomyLogic.clampToTether(a.x(), a.z(), tx, tz, a.radius());   // never leave the disc
        int ix = (int) Math.floor(c[0]);
        int iz = (int) Math.floor(c[1]);
        int iy = strollTargetY(level, body, ix, iz);
        BlockPos target = new BlockPos(ix, iy, iz);

        ensureSession(s, now);
        s.legStartX = body.getX();
        s.legStartZ = body.getZ();
        s.targetX = ix + 0.5;
        s.targetZ = iz + 0.5;
        final double txf = s.targetX, tzf = s.targetZ;
        s.nav = new PlayerNav(body, target, WALK_SPEED,
                () -> horizontalDistSqr(body, txf, tzf) <= ARRIVE_RADIUS * ARRIVE_RADIUS);
        s.phase = Phase.STROLL;
        s.actionDeadline = now + STROLL_TIMEOUT_TICKS;
    }

    private static void tickStroll(NumenPlayer body, State s, long now) {
        if (s.nav == null) { enterRest(s, now); return; }
        boolean timedOut = now >= s.actionDeadline;
        PlayerNav.Status st = s.nav.tick();
        if (st == PlayerNav.Status.RUNNING && !timedOut) return;
        // Leg ended (arrived / unreachable / timed out) → tally the distance actually walked, then rest.
        s.strollLegs++;
        s.strollBlocks += Math.sqrt(sqr(body.getX() - s.legStartX) + sqr(body.getZ() - s.legStartZ));
        s.nav.stop();
        s.nav = null;
        enterRest(s, now);
    }

    /** Anchor: owner position (online, same dimension, radius 24) else the captured home point (radius 8). */
    private static Anchor anchor(NumenPlayer body, State s) {
        ServerPlayer owner = body.resolveOwnerPlayer();
        boolean present = owner != null && owner.level() == body.level();
        int radius = AutonomyLogic.tetherRadius(present, ONLINE_TETHER_RADIUS, OFFLINE_TETHER_RADIUS);
        if (present) {
            return new Anchor(owner.getX(), owner.getZ(), radius);
        }
        if (s.homeAnchor == null) s.homeAnchor = body.blockPosition();
        return new Anchor(s.homeAnchor.getX() + 0.5, s.homeAnchor.getZ() + 0.5, radius);
    }

    /** A standable-ish Y for a wander target: the column surface, or the body's own level when it's underground. */
    private static int strollTargetY(ServerLevel level, NumenPlayer body, int x, int z) {
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int bodyY = body.blockPosition().getY();
        return (surface - bodyY > UNDERGROUND_MARGIN) ? bodyY : surface;
    }

    // ============================================================ rest / rhythm

    private static void enterRest(State s, long now) {
        s.phase = Phase.REST;
        s.restUntil = now + AutonomyLogic.restDuration(RANDOM.nextDouble(), REST_MIN_TICKS, REST_MAX_TICKS);
    }

    // ============================================================ dream-journal (informs-never-consults)

    private static void ensureSession(State s, long now) {
        if (s.sessionActive) return;
        s.sessionActive = true;
        s.sessionStart = now;
        // Tallies are deliberately NOT cleared here: an insubstantial interrupted session
        // suspends (suspendSession) and carries its legs/blocks/pickups/carriedTicks into
        // the next idle window; only a real flush (resetSession) zeroes the accumulator.
    }

    private static void recordPicked(State s, ItemStack stack) {
        s.picked.merge(stack.getHoverName().getString(), stack.getCount(), Integer::sum);
    }

    private static void noteUnpicked(State s, ItemEntity e) {
        if (!s.sessionActive) return;   // don't open a session just to record a miss
        ItemStack st = e.getItem();
        s.noticedUnpicked.merge(st.getHoverName().getString(), st.getCount(), Integer::sum);
    }

    private static void maybeFlushJournal(NumenPlayer body, State s, long now, boolean interrupted) {
        long effectiveTicks = s.carriedTicks + Math.max(0, now - s.sessionStart);
        boolean flush = AutonomyLogic.shouldFlushJournal(s.sessionActive, interrupted, effectiveTicks,
                JOURNAL_MAX_SESSION_TICKS, JOURNAL_MIN_SUBSTANCE_TICKS, s.strollLegs, totalPicked(s));
        if (flush) {
            emitJournal(body, s, now);
            resetSession(s);
        } else if (interrupted && s.sessionActive) {
            suspendSession(s, now);   // not enough substance yet — carry the tally, don't drop it
        }
    }

    /** Suspend an insubstantial interrupted session: bank its active time and keep every
     *  tally so the NEXT idle window continues the same journal entry instead of spawning
     *  a fresh three-second one. */
    private static void suspendSession(State s, long now) {
        s.carriedTicks += Math.max(0, now - s.sessionStart);
        s.sessionActive = false;
    }

    /** Batch-summary event: {@code <event kind="autonomy" provenance="observed">自主:…</event>}, non-urgent. */
    private static void emitJournal(NumenPlayer body, State s, long now) {
        long secs = Math.max(1, (s.carriedTicks + Math.max(0, now - s.sessionStart)) / 20);
        StringBuilder sb = new StringBuilder("自主:");
        boolean any = false;
        if (s.strollLegs > 0) {
            sb.append("漫步约 ").append(secs).append(" 秒(").append(s.strollLegs).append(" 程,约 ")
                    .append(Math.round(s.strollBlocks)).append(" 格)");
            any = true;
        }
        if (!s.picked.isEmpty()) {
            if (any) sb.append(",");
            sb.append("拾取 [").append(joinCounts(s.picked)).append("]");
            any = true;
        }
        if (!s.noticedUnpicked.isEmpty()) {
            if (any) sb.append(",");
            sb.append("背包已满,路过留意 [").append(joinCounts(s.noticedUnpicked)).append("]");
            any = true;
        }
        if (!any) return;   // zero-append guard (shouldFlushJournal already vets this)
        sb.append("。");
        Companions.emitEvent(body,
                "<event kind=\"autonomy\" provenance=\"observed\">" + xmlSafe(sb.toString()) + "</event>", false);
    }

    private static void resetSession(State s) {
        s.sessionActive = false;
        s.carriedTicks = 0;
        s.strollLegs = 0;
        s.strollBlocks = 0.0;
        s.picked.clear();
        s.noticedUnpicked.clear();
    }

    private static int totalPicked(State s) {
        int n = 0;
        for (int c : s.picked.values()) n += c;
        return n;
    }

    private static String joinCounts(Map<String, Integer> counts) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getKey()).append("×").append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    // ============================================================ helpers

    private static double horizontalDistSqr(NumenPlayer body, double tx, double tz) {
        return sqr(body.getX() - tx) + sqr(body.getZ() - tz);
    }

    private static double sqr(double v) { return v * v; }

    /** Neutralise XML delimiters in a display string (custom-named items could carry {@code < > &}). */
    private static String xmlSafe(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Lazily register the body-removal cleanup (self-registration, no touch to the init file). Called at the
     * top of {@link #tick}, so if the mount line is never added this never runs and the layer is inert.
     */
    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        CompanionLifecycle.onRemove(body -> {
            State s = STATES.remove(body.getUUID());
            if (s == null) return;
            if (s.nav != null) { s.nav.stop(); s.nav = null; }
            // Best-effort final flush of a productive session (emitEvent no-ops if the owner is offline).
            if (s.sessionActive && (s.strollLegs > 0 || totalPicked(s) > 0)) {
                long now = body.level() != null ? body.level().getGameTime() : s.sessionStart;
                emitJournal(body, s, now);
            }
        });
        Constants.LOG.info("[numen-core] idle-autonomy L3 scheduler registered (Wave A: stroll + pickup + journal)");
    }
}
