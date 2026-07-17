package com.dwinovo.numen.core.perception;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.combat.Decision;
import com.dwinovo.numen.core.combat.EngagementAssessment;
import com.dwinovo.numen.core.combat.McCombatAdapter;
import com.dwinovo.numen.core.pathing.exec.InputDriver;
import com.dwinovo.numen.core.pathing.exec.ReflexNav;
import com.dwinovo.numen.core.pathing.exec.TurtleDrive;
import com.dwinovo.numen.entity.CompanionLifecycle;
import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.entity.PerceptionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The <strong>reflex layer</strong> (the companion's spinal cord): instant,
 * server-side survival responses that run with <em>zero LLM involvement</em>,
 * then merely <em>inform</em> the brain afterwards.
 *
 * <h2>Why it exists</h2>
 * The LLM brain reacts in 2–10 s; environmental and combat damage ticks every
 * 1.5–2 s. Two field deaths motivated this layer: a companion mauled by a zombie
 * while five correct-but-too-slow LLM decisions were superseded, and a companion
 * suffocating inside a wall while the emergency turn was still in flight. Survival
 * cannot wait for the brain, so these reflexes act <em>this tick</em>.
 *
 * <h2>Spinal-cord contract — informs, never consults</h2>
 * A reflex performs a <strong>physical action only</strong> (break a block that is
 * suffocating the body; face-and-swing at, or sprint away from, an attacker) and
 * then pushes an append-only {@code <event kind="reflex">} note to the brain via
 * {@link Companions#emitEvent} — the same "teaching feedback" channel the rest of
 * perception uses. The brain is <em>told what already happened</em>, it is never
 * asked what to do. Crucially, a reflex <strong>NEVER cancels the brain's in-flight
 * turn or its queued tasks</strong>: it does not touch the agent loop, the reasoning
 * state, or {@code TaskQueue}. It may transiently drive the body's movement / aim
 * inputs (the {@link InputDriver} momentary {@code zza}/{@code xxa}/sprint/look that
 * vanilla player physics consumes next tick) while an episode is live — that is the
 * spinal action — but the brain's plan resumes untouched the moment the episode ends.
 *
 * <h2>Cost</h2>
 * {@link #tick} runs EVERY server tick per live companion (reflexes cannot be
 * throttled to the 10t/20t perception polls). It is deliberately trivial: an early
 * {@link #ENABLED} bail, an {@code isInWall()} probe (a 1–4 block eye-box scan), and
 * a distance check against a remembered attacker, and a null-check against a remembered
 * creeper (whose ref is refreshed only on a 20t scan, so the per-tick creeper cost is a
 * null-check plus at most one {@code distanceTo}). Real work happens only inside a live
 * episode, which is rare.
 *
 * <p>Server-thread only, exactly like {@link Perceptions} and {@link PerceptionState}
 * (both the poll pass that calls {@link #tick} and the {@link PerceptionEvents} hurt
 * seam this class subscribes run on the server tick thread), so the per-companion
 * reflex fields on {@link PerceptionState} need no synchronisation.
 */
public final class Reflexes {

    /**
     * Reflex master switch (default ON).
     *
     * <p>TODO(config): this pack has no reflex on/off gate yet. {@code CompanionPermissions}
     * governs {@code run_command} permission tiers, not survival reflexes, and there is no
     * SurvivalConfig-style file. Follow-up: wire this to a per-companion permission / config
     * toggle (e.g. a {@code /numenreflex <name> on|off} command backed by a JSON store, mirroring
     * {@code CompanionPermissions}). Kept {@code volatile} so a future command thread can flip it
     * without a visibility race against the server tick thread.
     */
    public static volatile boolean ENABLED = true;

    // ---- Reflex 1: suffocation escape ----
    /** Ticks between block-break attempts while still stuck in a wall. */
    private static final int SUFFOCATE_COOLDOWN = 20;

    // ---- Reflex 2: critical-HP fight-back / flee ----
    /** Engage the reflex when HP drops below this fraction of max after a living attacker's hit. */
    private static final float HP_CRITICAL_FRAC = 0.40f;
    /** End the episode once HP recovers above this fraction of max. */
    private static final float HP_RECOVER_FRAC = 0.50f;
    /** Within this range → fight back (face + swing); beyond it → flee. Matches hunt's melee reach. */
    private static final double MELEE_RANGE = 3.5;
    /** While the attacker is within this range, refresh "last seen" so the gone-timer only runs once it leaves. */
    private static final double TRACK_RADIUS = 16.0;
    /** One reflex swing per this many ticks (attack-cooldown-ish; the full-charge gate still applies). */
    private static final int SWING_COOLDOWN = 12;
    /** Sprint away from the attacker for at most this many ticks per flee bout, then stand (don't run off a cliff). */
    private static final int FLEE_MAX_TICKS = 60;
    /** Episode ends when the attacker has been dead / removed / unseen for this long. */
    private static final int ATTACKER_GONE_TICKS = 100;
    /** Chase hysteresis: a live episode survives ALL threats being unseen for this long before it closes.
     *  Crossing back outside the 8-block arming radius mid-chase is not safety — a chasing zombie re-enters
     *  within a second, and every premature close+reopen minted a fresh context note (the observed
     *  one-note-per-second reflex storm). */
    private static final int THREAT_CLEAR_GRACE_TICKS = 100;
    /** A clean-ENGAGE + recovered-HP verdict must hold for this long before it closes the episode. The raw
     *  condition flaps at the boundary (HP regen/damage around the 50% line, fight/flee verdict re-scored
     *  every REASSESS_PERIOD) and each flap re-opened the episode sub-second — the second storm engine. */
    private static final int RECOVER_SUSTAIN_TICKS = 60;
    /** Floor between two episode-start context notes. Whatever still churns episodes faster than this stays
     *  ONE story in the context, not a line per churn (同主题最高seq为准 — the newest note wins anyway). */
    private static final int CRITICAL_NOTE_COOLDOWN_TICKS = 200;
    /** How far ahead of the body the flee waypoint is placed (direction matters, not the exact point). */
    private static final double FLEE_LOOKAHEAD = 8.0;

    // ---- Reflex 2 (v3): engagement-engine flee/fight verdict (replaces the crude 25%-hit rule) ----
    /**
     * How often the deterministic combat engine ({@link McCombatAdapter#assessNearby}) is re-run during a live
     * critical episode. The v2 rule was a single scalar — "one blow ≥ 25% of max HP → flee unconditionally" —
     * which mis-called both directions: it fled a lucky big hit from a beatable mob, and it kept trading blows
     * into a 3-zombie pile that summed to a kill. v3 asks the engine ({@code ttkClear} vs a decreasing-k
     * survival sim, armor-degradation math, a dynamic one-shot guard) instead. It is O(entities) arithmetic, so
     * it runs on a throttle (this cadence), never every tick.
     */
    private static final int REASSESS_PERIOD = 10;
    /** Radius the engagement engine gathers hostiles over for a reflex assessment. */
    private static final double THREAT_SCAN_RADIUS = 16.0;

    // ---- Reflex 2 (v4): threat-triggered assessment (刀①) — no longer HP-gated ----
    /**
     * A hostile this close ARMS an engagement assessment even without a landed hit — the "满血就先评估" trigger
     * that replaces the old blood-gate (assessment used to wait for HP&lt;40%, so the body stood frozen while
     * arrows chipped it to 8 HP before anything fired). Scanned only on {@link #THREAT_TRIGGER_PERIOD} while the
     * body is idle (no live attacker, no episode), so the per-tick cost stays a null-check.
     */
    private static final double THREAT_TRIGGER_RADIUS = 8.0;
    /** Cadence of the idle hostile-proximity scan that arms the assessment (matches the perception proximity poll). */
    private static final int THREAT_TRIGGER_PERIOD = 20;

    // ---- Reflex 2 (v4): turtle-up (刀③) — the CORNERED escape hatch ----
    /** Re-evaluate a live turtle (threat散去 / 天亮 / 主人靠近 → break out) on this cadence. */
    private static final int TURTLE_REASSESS_PERIOD = 20;
    /** No hostile within this radius (and it's bright / owner near) → the turtle breaks out. */
    private static final double TURTLE_SAFE_RADIUS = 12.0;
    /** Owner within this range counts as "safe to emerge" regardless of daylight. */
    private static final double TURTLE_OWNER_NEAR = 8.0;
    /** One turtle per episode, and at most one per this many ticks (anti-abuse / anti-flicker). */
    private static final int TURTLE_COOLDOWN = 600;

    /**
     * The cached engagement verdict per companion in a LIVE critical episode ({@code nextReassess} = when to
     * re-run the engine). Populated on episode start, refreshed every {@link #REASSESS_PERIOD} ticks, and
     * dropped in {@link #endCriticalEpisode}, so it only ever holds entries for bodies currently in an episode —
     * no lifecycle leak (server-thread only, like the rest of the reflex state).
     */
    private static final Map<UUID, ReflexPlan> PLANS = new HashMap<>();

    private record ReflexPlan(long nextReassess, Decision decision, String limiting) {}

    /**
     * Reflex-only navigation sessions (刀②): a live {@link ReflexNav} per body currently fleeing along a planned
     * A* route instead of the crude straight-line dash. Created lazily when the critical reflex flees, ticked each
     * tick, and dropped (halted) the instant the flee ends, the verdict flips, or a higher reflex seizes the body —
     * so it only ever holds entries for bodies actively pathing to safety (server-thread only, like PLANS).
     */
    private static final Map<UUID, ReflexNav> NAVS = new HashMap<>();

    /**
     * Live turtle-up burrows (刀③): a {@link TurtleDrive} per body currently digging in / holed up because the
     * engagement engine returned CORNERED with a lethal race. Created when the turtle fires, ticked each tick,
     * and dropped the moment the burrow finishes or the episode ends (server-thread only, like PLANS).
     */
    private static final Map<UUID, TurtleState> TURTLES = new HashMap<>();

    // ---- Reflex 3 (v2): creeper panic sprint (HP-independent, pre-explosion) ----
    /** A creeper within this many blocks → unconditional sprint away, regardless of HP. */
    private static final double CREEPER_NEAR_RADIUS = 4.0;
    /** Widened trigger radius while the creeper is actively fusing (swelling / ignited) — it may blow this second. */
    private static final double CREEPER_FUSING_RADIUS = 7.0;
    /** The 20t scan looks this far for the nearest creeper to remember (so an approaching one is tracked before it enters trigger range). */
    private static final double CREEPER_SCAN_RADIUS = 8.0;
    /** Refresh the remembered creeper on this cadence (matches the perception proximity poll); the per-tick check is near-free otherwise. */
    private static final int CREEPER_SCAN_PERIOD = 20;
    /** Sprint away from a creeper for at most this many ticks per bout. */
    private static final int CREEPER_FLEE_MAX_TICKS = 40;
    /** Per-creeper cooldown between sprint bouts (keyed by entity id via the shared cooldown map). */
    private static final int CREEPER_COOLDOWN = 100;

    // ---- shared flee vector ----
    /** Flee toward the owner (protection instinct) only when the owner is online, same dimension, and within this range. */
    private static final double OWNER_FLEE_RANGE = 64.0;

    // ---- Reflex L0a: drowning escape (the water death — Fenn drowned 20s after login) ----
    /** Air supply (of {@link Entity#getMaxAirSupply} = 300) below which a submerged body starts swimming up. */
    private static final int DROWN_AIR_TRIGGER = 100;
    /** The episode ends once air recovers to/above this (head is clearly back in air). */
    private static final int DROWN_AIR_SAFE = 280;
    /** When a solid ceiling caps the straight-up route (underwater cave roof), search this far for the nearest air column. */
    private static final int DROWN_CEILING_SCAN_RADIUS = 8;
    /** How many blocks above the head count as a blocking ceiling for the straight-up check. */
    private static final int DROWN_CEILING_LOOKUP = 3;
    /** Re-run the (bounded) air-column search at most this often while swimming under a ceiling. */
    private static final int DROWN_RETARGET_PERIOD = 10;

    // ---- Reflex L0b: fire / lava retreat (the well-bottom lava death) ----
    /** Search this far (blocks) for water to dash into (extinguish) or the hazard to flee. */
    private static final double HEAT_SCAN_RADIUS = 4.0;
    /** Drive the heat-escape (toward water / away from lava) for at most this many ticks per bout. */
    private static final int HEAT_FLEE_MAX_TICKS = 40;
    /** Anti-flicker debounce between preemptive "紧邻岩浆" bouts (does NOT apply while actively burning). */
    private static final int HEAT_COOLDOWN = 60;

    // ---- Reflex L0c: auto-eat (global survival, lowest priority) ----
    /** Food level (of 20) at/below which the body auto-eats when nothing else is going on. */
    private static final int AUTOEAT_TRIGGER = 8;
    /** Stop eating once food reaches this. */
    private static final int AUTOEAT_STOP = 16;
    /** Being hit by a living attacker within this many ticks (受击) suppresses / interrupts auto-eat. */
    private static final int AUTOEAT_HIT_GUARD = 40;
    /** Back off this long after a bite that failed to consume anything, so we don't retry every tick. */
    private static final int EAT_FAIL_COOLDOWN = 100;

    /**
     * Per-companion state for the L0 reflexes, keyed by body UUID. PerceptionState is owned by another
     * subsystem and off-limits, so — exactly like {@link #PLANS} — the L0 reflexes keep their own
     * episode/cooldown state here. Created lazily by {@link #extra} only when a reflex actually needs to
     * remember something, dropped by {@link #tick} the moment every episode has closed ({@link ExtraState#idle()}),
     * with a hard backstop on body removal wired in {@link #register()}. Holds no entity references, so a
     * stray entry (a body removed mid-episode before the backstop) is a tiny, self-reused record like PLANS.
     * Server-thread only, like the rest of the reflex state.
     */
    private static final Map<UUID, ExtraState> EXTRA = new HashMap<>();

    private static final class ExtraState {
        /**
         * Game time through which a higher-priority environmental reflex (drowning / fire-lava) owns the body's
         * movement inputs this tick; the creeper and critical-HP reflexes yield while {@code envMoveUntil >= now}.
         * A per-tick signal (set to {@code now} when such a reflex drives), self-expiring next tick.
         */
        long envMoveUntil = Long.MIN_VALUE;

        // drowning
        boolean drowning;        // a drowning episode is live (gates the once-per-episode note)
        long drownRetargetAt;    // next allowed air-column search
        Vec3 drownTarget;        // cached horizontal swim target under a ceiling; null = swim straight up

        // fire / lava
        boolean heatEpisode;     // gates the once-per-episode note
        long heatFleeUntil;      // heat-escape drive window (now < this → a bout is live)
        long heatCooldownUntil;  // anti-flicker debounce between preemptive bouts

        // auto-eat
        boolean eating;          // an eat episode is live
        boolean eatStarted;      // the current bite's native use has been fired
        int eatRestoreSlot = -1; // hotbar slot to re-select once eating ends (恢复原手持)
        int eatBeforeFood;       // food level when the current bite's use started (detects a bite that didn't take)
        String eatLabel = "";    // display name of the food being eaten (for the note)

        /** True once every L0 episode has closed, so the entry can be dropped. */
        boolean idle() {
            return !drowning && !heatEpisode && !eating;
        }
    }

    /** Get-or-create this body's L0 reflex state (called only when a reflex needs to persist something). */
    private static ExtraState extra(NumenPlayer body) {
        return EXTRA.computeIfAbsent(body.getUUID(), k -> new ExtraState());
    }

    /** True while a higher-priority environmental reflex (drowning / fire-lava) is driving movement this tick. */
    private static boolean envOwnsMovement(NumenPlayer body, long now) {
        ExtraState ex = EXTRA.get(body.getUUID());
        return ex != null && ex.envMoveUntil >= now;
    }

    private static boolean registered;

    private Reflexes() {}

    /**
     * Subscribe the hurt seam so we always remember the last LIVING attacker (the fight-back target).
     * Idempotent; called from {@link Perceptions#register()}.
     */
    public static void register() {
        if (registered) return;
        registered = true;
        PerceptionEvents.onHurt(Reflexes::onHurt);
        // Hard backstop: drop this body's reflex state when it leaves the world (mirrors the seam
        // Perceptions uses to drop PerceptionState). Covers the rare case of a body removed mid-episode,
        // so neither EXTRA nor PLANS can accumulate stale entries.
        CompanionLifecycle.onRemove(body -> {
            UUID id = body.getUUID();
            EXTRA.remove(id);
            PLANS.remove(id);
            ReflexNav nav = NAVS.remove(id);
            if (nav != null) nav.halt();
            TurtleState t = TURTLES.remove(id);
            if (t != null) t.drive.stop();
        });
        Constants.LOG.info("[numen-core] reflex layer (spinal cord) registered");
    }

    /**
     * 刀②/身体仲裁: is a movement-owning reflex episode currently driving this body? The task dispatcher queries
     * this before it advances a companion task, so a running task's navigator never fights the reflex for the
     * body's inputs (the reflex layer ticks AFTER the dispatcher, so it already wins the input write each tick;
     * this mutex additionally stops the task's own A* / viz from running uselessly and from leaving sneak /
     * dig side-effects mid-flee). Cheap: a couple of field reads. Server-thread only, like the rest of the layer.
     */
    public static boolean ownsBody(NumenPlayer body) {
        if (!ENABLED || Perceptions.isCreative(body)) return false;
        PerceptionState st = Perceptions.stateFor(body);
        long now = body.level().getGameTime();
        if (st.reflexCriticalEpisode) return true;          // fighting / fleeing (ReflexNav) / turtling
        if (now < st.reflexCreeperFleeUntil) return true;   // creeper panic sprint
        ExtraState ex = EXTRA.get(body.getUUID());
        return ex != null && (ex.drowning || ex.heatEpisode);   // drowning / fire-lava retreat
    }

    /**
     * The per-tick reflex pass for one live companion. Called every tick from
     * {@link Perceptions#tickOne} (before the throttled perception polls, so survival acts first).
     */
    static void tick(NumenPlayer body, PerceptionState st, long now) {
        if (!ENABLED) return;
        if (Perceptions.isCreative(body)) return;   // creative can't die — every survival reflex is noise
        if (body.isDeadOrDying()) return;   // the corpse is handled by NumenPlayer#tick / Companions#onDeath
        // Priority ladder (highest first): 窒息 > 溺水 > 火/岩浆 > 苦力怕 > 濒危战逃 > 自动进食. Each reflex
        // self-yields to a higher one that has claimed the body this tick (movement claims via envMoveUntil /
        // the creeper flee window), so run order == priority.
        if (body.level() instanceof ServerLevel level) {
            suffocationReflex(body, st, now, level);   // 窒息 — breaks the trapping block (no movement claim)
            drowningReflex(body, st, now, level);      // 溺水 — swim up (claims movement over creeper/critical)
            fireLavaReflex(body, st, now, level);      // 火/岩浆 — flee heat (yields to drowning; claims over creeper/critical)
            creeperReflex(body, st, now, level);       // 苦力怕 — pre-explosion sprint (yields to drowning/heat)
        }
        criticalHpReflex(body, st, now);               // 濒危战逃 (yields to creeper + drowning/heat)
        autoEatReflex(body, st, now);                  // 自动进食 — lowest; only when nothing else is happening

        // Drop this body's L0 state once every episode has closed (episode-scoped lifetime, like PLANS).
        ExtraState ex = EXTRA.get(body.getUUID());
        if (ex != null && ex.idle()) EXTRA.remove(body.getUUID());
    }

    // ============================================================ hurt seam (attacker memory)

    /**
     * Remember the last LIVING attacker + tick (fight-back target) and the raw damage of this hit
     * ({@code HurtInfo.amount}), so the critical-HP reflex can tell a heavy hitter (flee unconditionally)
     * from a small one (v1 distance rule). Both are refreshed on every landed hit, so they always describe
     * the <em>current</em> attacker's most recent blow.
     */
    private static void onHurt(PerceptionEvents.HurtInfo info) {
        if (!ENABLED) return;
        Entity attacker = livingAttacker(info.source());
        if (attacker == null) return;
        NumenPlayer body = info.body();
        PerceptionState st = Perceptions.stateFor(body);
        st.reflexAttacker = attacker;
        st.reflexAttackerSeenTick = body.level().getGameTime();
        st.reflexLastHitDamage = info.amount();
    }

    /** The causing LIVING entity (an arrow's shooter, not the arrow), or null if the source isn't living. */
    private static Entity livingAttacker(DamageSource source) {
        Entity e = source.getEntity();
        return e instanceof LivingEntity ? e : null;
    }

    // ============================================================ reflex 1: suffocation escape

    /**
     * The wall death. Trigger: {@code body.isInWall()} — vanilla's own suffocation probe (a thin
     * eye-height box tested for a suffocating, colliding block). Response: instantly break the
     * suffocating block(s) at head and body level with {@code destroyBlock(..., dropResources=true)}
     * (honest survival — the blocks drop as items), no pathfinding and no LLM. One break attempt per
     * {@link #SUFFOCATE_COOLDOWN} ticks; a single non-urgent note per episode. The episode clears the
     * moment the body is free again, so a later suffocation is a fresh episode with a fresh note.
     */
    private static void suffocationReflex(NumenPlayer body, PerceptionState st, long now, ServerLevel level) {
        if (!body.isInWall()) {                 // cheap early-bail: not suffocating → close any episode
            st.reflexSuffocateEpisode = false;
            return;
        }
        if (now < st.reflexSuffocateCooldownUntil) return;   // still cooling down between attempts
        st.reflexSuffocateCooldownUntil = now + SUFFOCATE_COOLDOWN;

        freeFromWall(body, level);

        if (!st.reflexSuffocateEpisode) {
            st.reflexSuffocateEpisode = true;   // emit ONCE per episode (non-urgent — it already acted)
            emitObserved(body, "本能反应:你被卡在方块里窒息,已破坏头部方块脱困(HP "
                    + Perceptions.fmt(body.getHealth()) + "/" + Perceptions.fmt(body.getMaxHealth()) + ")。");
        }
    }

    /** Break every suffocating block in the head box and the body box (one layer below). */
    private static void freeFromWall(NumenPlayer body, ServerLevel level) {
        Vec3 eye = body.getEyePosition();
        float width = body.getBbWidth() * 0.8f;                 // the same 0.8 slack vanilla isInWall() uses
        breakSuffocatingLayer(body, level, eye, width);         // head
        breakSuffocatingLayer(body, level, eye.add(0.0, -1.0, 0.0), width);   // body / torso
    }

    /**
     * Destroy the suffocating blocks in a thin box around {@code center} — mirrors vanilla
     * {@code Entity#isInWall}'s per-block predicate ({@code !isAir && isSuffocating}). Positions are
     * collected first (immutable) so destroying a block never disturbs the lazy position stream.
     */
    private static void breakSuffocatingLayer(NumenPlayer body, ServerLevel level, Vec3 center, float width) {
        AABB box = AABB.ofSize(center, width, 1.0E-6, width);
        List<BlockPos> hits = BlockPos.betweenClosedStream(box)
                .filter(pos -> {
                    BlockState s = level.getBlockState(pos);
                    return !s.isAir() && s.isSuffocating(level, pos);
                })
                .map(BlockPos::immutable)
                .toList();
        for (BlockPos pos : hits) {
            level.destroyBlock(pos, true, body);   // dropResources=true → items drop (honest survival)
        }
    }

    // ============================================================ reflex 2: critical-HP fight-back / flee

    /**
     * The mauled-while-thinking death, decided by the deterministic engagement engine (§3.1/§3.2). This is the
     * v4 rewrite that closes the survival loop after the "5 分钟死 4 次" night:
     *
     * <ul>
     *   <li><b>刀① 拆血门 — threat-triggered, no longer HP-gated.</b> The assessment now arms on <em>contact OR
     *       proximity</em>: a LIVING attacker having recently hit the body, OR a hostile within
     *       {@link #THREAT_TRIGGER_RADIUS} (scanned on the {@link #THREAT_TRIGGER_PERIOD} idle cadence). At full
     *       HP the engine's verdict is executed immediately — no more standing frozen while arrows chip the body
     *       to 8 HP. {@link #HP_CRITICAL_FRAC} survives only as the 加急线: while wounded, {@link #planFor} is
     *       forced to re-assess this tick (bypassing the {@link #REASSESS_PERIOD} throttle).</li>
     *   <li><b>Verdict execution.</b> {@link Decision#ENGAGE}/{@link Decision#CORNERED} within
     *       {@link #MELEE_RANGE} → fight back (native swing, one per {@link #SWING_COOLDOWN} at full charge);
     *       every other verdict → flee.</li>
     *   <li><b>刀② 智能逃跑.</b> Flight now runs a PLANNED A* retreat ({@link ReflexNav}) toward a scored safe
     *       cell, falling back to the v1 straight-line dash only when nothing is reachable — no more sprinting off
     *       a cliff or into a second pack.</li>
     *   <li><b>刀③ 第四选项.</b> A non-creeper {@link Decision#CORNERED} (打不过且跑不掉,评估判死) → 龟缩
     *       ({@link TurtleDrive}): burrow, cap, hunker down, and break out when the coast clears.</li>
     * </ul>
     *
     * <p>A dangerous verdict keeps us fleeing/turtling until the threat is gone (an HP rebound is not safety — one
     * more blow can be lethal); a clean-ENGAGE verdict ends the episode on HP recovery above
     * {@link #HP_RECOVER_FRAC}. The episode also ends when the threat is dead / gone for
     * {@link #ATTACKER_GONE_TICKS}. Yields movement to a live creeper sprint / drowning / fire-lava reflex. One
     * non-urgent note per episode (the reflex has already reacted; it informs, never consults).
     */
    private static void criticalHpReflex(NumenPlayer body, PerceptionState st, long now) {
        Entity attacker = st.reflexAttacker;
        boolean attackerLive = attacker != null && attacker.isAlive() && !attacker.isRemoved()
                && attacker.level() == body.level();
        boolean recentContact = attackerLive && (now - st.reflexAttackerSeenTick) <= ATTACKER_GONE_TICKS;

        // 刀①: the assessment is no longer HP-gated. Without a landed hit we still ARM it on proximity — a hostile
        // within THREAT_TRIGGER_RADIUS. Scanned on a throttle (only while there's no live attacker), so the idle
        // per-tick cost stays a null-check. This is the "满血就先评估" trigger that killed the old blood-gate.
        if (!recentContact && now % THREAT_TRIGGER_PERIOD == 0) {
            st.reflexNearHostile = (body.level() instanceof ServerLevel lvl)
                    ? nearestHostile(body, lvl, THREAT_TRIGGER_RADIUS) : null;
        }
        boolean nearHostileLive = st.reflexNearHostile != null && st.reflexNearHostile.isAlive()
                && !st.reflexNearHostile.isRemoved() && st.reflexNearHostile.level() == body.level()
                && body.distanceTo(st.reflexNearHostile) <= THREAT_TRIGGER_RADIUS;

        // The entity the reflex steers toward when it fights/flees: the recorded attacker if any, else the
        // nearest hostile the proximity scan armed us with (so a pre-hit approach is handled).
        Entity threat = recentContact ? attacker : (nearHostileLive ? st.reflexNearHostile : null);

        // Nothing to assess (no attacker, no nearby hostile) and no live episode → close any open one, bail cheap.
        if (threat == null && !st.reflexCriticalEpisode) {
            PLANS.remove(body.getUUID());
            return;
        }
        if (threat == null) {
            if (turtling(body)) {   // threat gone but a burrow is still open — let the turtle break out gracefully
                tickTurtle(body, st, now);
                return;
            }
            // Chase hysteresis: "no threat this tick" is not "safe" — a chasing mob that slipped outside the
            // arming radius re-enters within a second, and closing+reopening the episode minted a context
            // note per crossing. Hold the episode through the grace window: finish any planned retreat,
            // plan nothing new (no threat to steer from), close only once the calm has lasted.
            if (now - st.reflexThreatSeenTick <= THREAT_CLEAR_GRACE_TICKS) {
                ReflexNav nav = NAVS.get(body.getUUID());
                if (nav != null && nav.tick() != ReflexNav.Status.RUNNING) dropNav(body);
                return;
            }
            endCriticalReflex(st, body);
            return;
        }
        st.reflexThreatSeenTick = now;

        float max = body.getMaxHealth();
        float hp = body.getHealth();
        boolean wounded = hp < max * HP_CRITICAL_FRAC;

        // Ask the deterministic engine for the fight/flee verdict (throttled to REASSESS_PERIOD). HP_CRITICAL_FRAC
        // is now only the 加急线: when wounded, force an immediate re-assessment (bypass the throttle) so a plunging
        // HP bar is reflected this tick instead of up to 10 ticks late. The provoked entity is the recorded
        // attacker (so a provoking neutral golem is scored); a pure proximity trigger passes null (general scan).
        ReflexPlan plan = planFor(body, recentContact ? attacker : null, now, wounded);
        boolean dangerous = plan.decision() != Decision.ENGAGE;   // the engine says fighting is not a clean win

        if (st.reflexCriticalEpisode) {
            // A dangerous verdict never ends on an HP rebound — one more blow can be lethal; it ends only when
            // the threat is gone (handled above). A clean-ENGAGE verdict ends the episode on HP recovery —
            // but only a SUSTAINED one: the raw condition flaps at the boundary (HP regen/damage around the
            // recover line, verdict re-scored every REASSESS_PERIOD) and each flap re-opened the episode
            // sub-second, minting a context note per flap (the second storm engine).
            boolean recovered = !dangerous && hp > max * HP_RECOVER_FRAC && !turtling(body);
            if (recovered) {
                if (st.reflexCalmSinceTick == 0) st.reflexCalmSinceTick = now;
                if (now - st.reflexCalmSinceTick >= RECOVER_SUSTAIN_TICKS) {
                    endCriticalReflex(st, body);
                    return;
                }
            } else {
                st.reflexCalmSinceTick = 0;
            }
            actCritical(body, st, now, threat, plan);
            return;
        }

        // Not engaged yet. Start when the engine says the fight is not a clean win (dangerous — flee / avoid /
        // kite / cornered), or the body is already wounded. A clean ENGAGE at healthy HP is left to the brain /
        // hunt task (informs, never consults — the reflex only steps in when survival is in question).
        if (dangerous || wounded) {
            st.reflexCriticalEpisode = true;
            st.reflexFleeUntil = 0;
            st.reflexCalmSinceTick = 0;
            // Note floor: whatever still churns episodes faster than the cooldown stays ONE story in the
            // context, not a line per churn — the spinal cord keeps acting either way, only the note is gated.
            if (st.ready("reflex_critical_note", now)) {
                st.arm("reflex_critical_note", now, CRITICAL_NOTE_COOLDOWN_TICKS);
                emit(body, "本能反应:遭遇威胁,已本能反击/撤离(目标:"
                        + Perceptions.safe(threat.getName().getString())
                        + (plan.limiting().isEmpty() ? "" : ";评估:" + plan.limiting()) + ")。");
            }
            actCritical(body, st, now, threat, plan);
        }
    }

    /** End a critical episode cleanly: stop any flee-drive / nav / turtle we owned, then reset the state. */
    private static void endCriticalReflex(PerceptionState st, NumenPlayer body) {
        if (st.reflexFleeUntil != 0) InputDriver.halt(body);   // stop the straight-line flee drive we owned
        endCriticalEpisode(st, body);
    }

    /** The nearest live hostile ({@link Monster}) within {@code radius}, or null — the idle proximity trigger's scan. */
    private static Entity nearestHostile(NumenPlayer body, ServerLevel level, double radius) {
        AABB box = body.getBoundingBox().inflate(radius);
        Monster nearest = null;
        double best = Double.MAX_VALUE;
        for (Monster m : level.getEntitiesOfClass(Monster.class, box)) {
            if (!m.isAlive()) continue;
            double d = body.distanceToSqr(m);
            if (d < best) { best = d; nearest = m; }
        }
        return nearest;
    }

    /**
     * The engagement verdict for this body, refreshed at most once per {@link #REASSESS_PERIOD} ticks. The
     * engine ({@link McCombatAdapter#assessNearby}) is O(entities) arithmetic plus one hostile scan, so it must
     * not run every tick; the result is cached in {@link #PLANS} keyed by body UUID and dropped by
     * {@link #endCriticalEpisode} (and when the attacker leaves). The recorded {@code attacker} is passed as the
     * provoked entity so a currently-attacking neutral (iron golem / enderman) is scored as the threat it is.
     */
    private static ReflexPlan planFor(NumenPlayer body, Entity attacker, long now, boolean force) {
        UUID key = body.getUUID();
        ReflexPlan cached = PLANS.get(key);
        if (!force && cached != null && now < cached.nextReassess()) return cached;   // force = the wounded 加急线
        EngagementAssessment a = McCombatAdapter.assessNearby(body, attacker, THREAT_SCAN_RADIUS);
        ReflexPlan plan = new ReflexPlan(now + REASSESS_PERIOD, a.decision(), a.limitingFactor());
        PLANS.put(key, plan);
        return plan;
    }

    /**
     * One tick of the engaged behaviour, steered by the engine's {@code decision}. {@link Decision#ENGAGE} or
     * {@link Decision#CORNERED} → fight back when the attacker is within {@link #MELEE_RANGE} (CORNERED means
     * flight is not viable, so 打得赢就打 — fighting beats standing frozen). Every other verdict
     * ({@link Decision#FLEE}/{@link Decision#KITE}/{@link Decision#AVOID}), or an out-of-reach attacker → flee.
     * Yields movement to a live creeper sprint, which outranks a melee exchange (an explosion is instant death).
     */
    private static void actCritical(NumenPlayer body, PerceptionState st, long now, Entity threat, ReflexPlan plan) {
        // A live turtle owns the body until it seals, holds, and breaks out — tick it and nothing else.
        if (turtling(body)) { tickTurtle(body, st, now); return; }

        if (now < st.reflexCreeperFleeUntil || envOwnsMovement(body, now)) {
            dropNav(body);   // a higher reflex (creeper / drowning / fire-lava) drives — release our nav, keep the episode
            return;
        }

        double dist = body.distanceTo(threat);
        // Keep "last seen" fresh while the recorded attacker is still nearby so the gone-timer only runs once it leaves.
        if (dist <= TRACK_RADIUS && threat == st.reflexAttacker) st.reflexAttackerSeenTick = now;

        Decision decision = plan.decision();

        // 刀③: CORNERED (打不过且跑不掉) with a non-creeper threat → 龟缩. The engine's non-creeper CORNERED cases all
        // fail the survival sim AND flight, so CORNERED itself is the "评估判死" signal; a creeper CORNERED must NOT
        // turtle (an adjacent blast opens the cap) and is left to the creeper reflex / flee. One per episode, 600t CD.
        if (decision == Decision.CORNERED && !plan.limiting().contains("苦力怕")
                && now >= st.reflexTurtleCooldownUntil && !st.reflexTurtledThisEpisode) {
            enterTurtle(body, st, now);
            return;
        }

        boolean fight = decision == Decision.ENGAGE || decision == Decision.CORNERED;
        if (fight && dist <= MELEE_RANGE) {
            dropNav(body);
            fightBack(body, st, now, threat);
        } else {
            flee(body, st, now, threat);
        }
    }

    /**
     * Plant, face the attacker, and swing — the exact native-melee idiom {@code HuntCompanionTask}
     * uses ({@code attack} + {@code resetAttackStrengthTicker} + {@code swing}), gated to one hit per
     * {@link #SWING_COOLDOWN} and only at full attack charge (≥0.95) for full damage.
     */
    private static void fightBack(NumenPlayer body, PerceptionState st, long now, Entity attacker) {
        st.reflexFleeUntil = 0;   // in range now — cancel any flee bout
        InputDriver.halt(body);   // stop moving; plant to swing
        InputDriver.lookAt(body, attacker.getEyePosition());
        if (now >= st.reflexSwingUntil && body.getAttackStrengthScale(0.0f) >= 0.95f) {
            st.reflexSwingUntil = now + SWING_COOLDOWN;
            body.attack(attacker);                        // native damage / cooldown / sweep / knockback / crit
            body.resetAttackStrengthTicker();
            body.swing(InteractionHand.MAIN_HAND);
        }
    }

    /**
     * 刀②: flee along a PLANNED A* retreat ({@link ReflexNav}) instead of the v1 straight-line dash that ran the
     * body off cliffs and into second packs. The nav is created at most once per {@link #FLEE_MAX_TICKS} bout
     * (a Monster scan + candidate ranking is not free); while it runs it owns movement. On ARRIVED (reached a
     * safe cell 16–24 blocks out) it is dropped and the episode re-evaluates next tick. On FAILED (no reachable
     * escape cell) it falls back to the bounded straight-line {@link #driveFlee} for a bout before re-planning.
     */
    private static void flee(NumenPlayer body, PerceptionState st, long now, Entity threat) {
        ReflexNav nav = NAVS.get(body.getUUID());
        if (nav != null) {
            switch (nav.tick()) {
                case RUNNING -> { st.reflexFleeUntil = 0; return; }               // the nav drives — no straight bout
                case ARRIVED -> { dropNav(body); st.reflexFleeUntil = 0; InputDriver.halt(body); return; }
                case FAILED -> { dropNav(body); st.reflexFleeUntil = now + FLEE_MAX_TICKS; }   // dash instead, this bout
            }
        }
        // A straight-line bout is in progress → drive it out before planning a fresh route (bounds the re-scan cost).
        if (now < st.reflexFleeUntil) { driveFlee(body, threat); return; }
        // No nav, no active bout → plan a fresh A* retreat; fall back to a bounded straight dash if none is reachable.
        ReflexNav fresh = ReflexNav.flee(body, threat);
        if (fresh != null) {
            NAVS.put(body.getUUID(), fresh);
            if (fresh.tick() == ReflexNav.Status.RUNNING) { st.reflexFleeUntil = 0; return; }
            dropNav(body);   // instantly arrived / failed — clean up and dash this tick
        }
        st.reflexFleeUntil = now + FLEE_MAX_TICKS;
        driveFlee(body, threat);
    }

    /** Stop and forget this body's flee nav (halts its {@link PlayerNav}, no residue). */
    private static void dropNav(NumenPlayer body) {
        ReflexNav nav = NAVS.remove(body.getUUID());
        if (nav != null) nav.halt();
    }

    private static void endCriticalEpisode(PerceptionState st, NumenPlayer body) {
        st.reflexCriticalEpisode = false;
        st.reflexFleeUntil = 0;
        st.reflexCalmSinceTick = 0;
        st.reflexAttacker = null;       // don't pin a possibly-removed entity; the next hit re-arms it
        st.reflexLastHitDamage = 0.0f;  // clear the last-hit latch with the attacker
        st.reflexNearHostile = null;    // drop the proximity-trigger ref with the episode
        st.reflexTurtledThisEpisode = false;   // the per-episode turtle gate re-arms for the next episode
        dropNav(body);                  // stop any flee nav
        TurtleState t = TURTLES.remove(body.getUUID());
        if (t != null) t.drive.stop();  // abandon any burrow (release sneak / inputs)
        PLANS.remove(body.getUUID());   // the cached verdict only lives as long as the episode does
    }

    // ============================================================ turtle-up (刀③)

    /** Per-body turtle bookkeeping: the muscle + whether we've emitted the once-per-burrow "封闭掩体" note. */
    private static final class TurtleState {
        final TurtleDrive drive;
        boolean sealedNoted;
        TurtleState(TurtleDrive drive) { this.drive = drive; }
    }

    /** True while a turtle burrow is live for this body. */
    private static boolean turtling(NumenPlayer body) {
        return TURTLES.containsKey(body.getUUID());
    }

    /** Begin a turtle burrow: drop any flee nav, plant, arm the once-per-episode + cooldown gates, emit the note. */
    private static void enterTurtle(NumenPlayer body, PerceptionState st, long now) {
        dropNav(body);
        InputDriver.halt(body);
        st.reflexFleeUntil = 0;
        st.reflexTurtledThisEpisode = true;
        st.reflexTurtleCooldownUntil = now + TURTLE_COOLDOWN;
        TURTLES.put(body.getUUID(), new TurtleState(new TurtleDrive(body)));
        emit(body, "本能龟缩:打不过也跑不掉,正在原地下挖并封顶,缩进掩体等待威胁散去。");
    }

    /** One tick of a live turtle: dig / seal / hold, re-evaluate on cadence, and end the episode on break-out. */
    private static void tickTurtle(NumenPlayer body, PerceptionState st, long now) {
        TurtleState ts = TURTLES.get(body.getUUID());
        if (ts == null) return;
        switch (ts.drive.tick()) {
            case WORKING, EMERGING -> { /* in progress — keep ticking */ }
            case SEALED -> {
                if (!ts.sealedNoted) {
                    ts.sealedNoted = true;
                    emit(body, "本能龟缩:已封闭掩体,等待威胁散去。");
                }
                // Re-evaluate on cadence: coast clear (no hostile near AND bright / owner here) → break out.
                if (now % TURTLE_REASSESS_PERIOD == 0 && turtleCoastClear(body)) {
                    ts.drive.breakOut();
                }
            }
            case DONE -> {
                emit(body, "本能龟缩:威胁散去,已破土而出。");
                endCriticalEpisode(st, body);   // burrow over — close the episode, hand the body back to the brain
            }
        }
    }

    /** The turtle emerges when no hostile is within {@link #TURTLE_SAFE_RADIUS} and it's bright / the owner is here. */
    private static boolean turtleCoastClear(NumenPlayer body) {
        if (!(body.level() instanceof ServerLevel level)) return true;
        if (nearestHostile(body, level, TURTLE_SAFE_RADIUS) != null) return false;
        if (level.isBrightOutside()) return true;
        ServerPlayer owner = body.resolveOwnerPlayer();
        return owner != null && owner.level() == body.level() && body.distanceTo(owner) <= TURTLE_OWNER_NEAR;
    }

    // ============================================================ reflex 3: creeper panic sprint

    /**
     * The looting one-shot death. A creeper deals its whole payload in a single explosion burst that no
     * HP-triggered reflex can survive, so this fires BEFORE any damage, purely on proximity and entirely
     * HP-independent: a creeper within {@link #CREEPER_NEAR_RADIUS} (widened to {@link #CREEPER_FUSING_RADIUS}
     * once it is actively fusing) triggers a {@link #CREEPER_FLEE_MAX_TICKS}-tick sprint away, at most one bout
     * per {@link #CREEPER_COOLDOWN} per creeper. This owns movement over the critical-HP reflex (which yields).
     *
     * <p><strong>Cost.</strong> The remembered creeper is refreshed only every {@link #CREEPER_SCAN_PERIOD}
     * ticks (one targeted {@code getEntitiesOfClass} on the perception proximity cadence); every other tick
     * this method is a null-check plus, at most, one live {@code distanceTo} against the remembered ref — so
     * it is near-free unless a creeper is genuinely close.
     */
    private static void creeperReflex(NumenPlayer body, PerceptionState st, long now, ServerLevel level) {
        // 20t refresh of the remembered creeper — the ONLY entity query; keeps the per-tick path near-free.
        if (now % CREEPER_SCAN_PERIOD == 0) {
            st.reflexCreeper = nearestCreeper(body, level);
        }

        Entity ref = st.reflexCreeper;
        Creeper creeper = (ref instanceof Creeper c && c.isAlive() && !c.isRemoved() && c.level() == body.level())
                ? c : null;
        if (creeper == null) st.reflexCreeper = null;   // exploded / died / changed level between scans

        // Trigger a fresh sprint bout when the remembered creeper is inside the (fuse-widened) danger radius
        // and its per-creeper cooldown has elapsed. Guarded so a live bout is never restarted.
        if (creeper != null && now >= st.reflexCreeperFleeUntil) {
            boolean fusing = creeper.isIgnited() || creeper.getSwellDir() > 0;   // about to detonate → react from further out
            double radius = fusing ? CREEPER_FUSING_RADIUS : CREEPER_NEAR_RADIUS;
            if (body.distanceTo(creeper) <= radius) {                            // distance check first — no allocation until in range
                String cd = "reflex_creeper:" + creeper.getId();                 // per-creeper cooldown key (built only when triggering)
                if (st.ready(cd, now)) {
                    st.arm(cd, now, CREEPER_COOLDOWN);
                    st.reflexCreeperFleeUntil = now + CREEPER_FLEE_MAX_TICKS;
                    emit(body, "本能反应:苦力怕逼近,已紧急撤离。");
                }
            }
        }

        // Drive the sprint while a bout is live; halt ONCE when it ends, then release (InputDriver inputs
        // are momentary — a live bout must re-assert them each tick, and a spent bout must zero them once
        // so the body doesn't keep sprinting; after release the brain owns movement again).
        if (st.reflexCreeperFleeUntil != 0) {
            if (creeper != null && now < st.reflexCreeperFleeUntil) {
                if (!envOwnsMovement(body, now)) {
                    driveFlee(body, creeper);      // bout live → sprint away (movement priority over the critical reflex)
                }                                  // else: drowning / fire-lava (higher priority) drives — yield, keep the bout
            } else {
                InputDriver.halt(body);            // bout spent, or creeper gone mid-bout → stop our drive, once
                st.reflexCreeperFleeUntil = 0;     // release movement back to the brain / critical reflex
            }
        }
    }

    /** The nearest live creeper within {@link #CREEPER_SCAN_RADIUS}, or null — the sole 20t entity query. */
    private static Creeper nearestCreeper(NumenPlayer body, ServerLevel level) {
        AABB box = body.getBoundingBox().inflate(CREEPER_SCAN_RADIUS);
        List<Creeper> near = level.getEntitiesOfClass(Creeper.class, box);
        Creeper nearest = null;
        double best = Double.MAX_VALUE;
        for (Creeper c : near) {
            if (!c.isAlive()) continue;
            double d = body.distanceToSqr(c);
            if (d < best) {
                best = d;
                nearest = c;
            }
        }
        return nearest;
    }

    // ============================================================ reflex L0a: drowning escape

    /**
     * The water death (Fenn drowned 20s after login: submerged, no instinct to surface). Trigger: the body is
     * submerged and its {@link Entity#getAirSupply air supply} has fallen below {@link #DROWN_AIR_TRIGGER}
     * (of {@link Entity#getMaxAirSupply} = 300). Response: press the swim-up stroke every tick
     * ({@link InputDriver#jump} = vanilla {@code jumpInLiquid} +0.04/tick, the same primitive that keeps
     * Baritone at the surface). If a solid ceiling caps the straight-up route (an underwater cave roof), search a
     * small radius ({@link #DROWN_CEILING_SCAN_RADIUS}) for the nearest air column and swim toward it while still
     * stroking up. The episode ends when the body leaves the water or air recovers to {@link #DROWN_AIR_SAFE}.
     * One note per episode. Highest movement priority below suffocation — claims the body over the creeper and
     * critical reflexes for the tick it drives.
     *
     * <p><strong>Cost.</strong> The overwhelmingly common tick is a single {@code getAirSupply()} read and an
     * {@code air >= 280} compare, then return — near-free. The block scans run only inside a live episode (rare)
     * and the air-column search is throttled to {@link #DROWN_RETARGET_PERIOD}.
     */
    private static void drowningReflex(NumenPlayer body, PerceptionState st, long now, ServerLevel level) {
        int air = body.getAirSupply();
        if (air >= DROWN_AIR_SAFE) {                 // 280+/300 → not drowning (the near-universal case): cheapest bail
            ExtraState ex = EXTRA.get(body.getUUID());
            if (ex != null && ex.drowning) ex.drowning = false;   // an episode that just resolved (surfaced / re-oxygenated)
            return;
        }
        boolean submerged = body.isInWater() || body.isUnderWater();
        ExtraState cur = EXTRA.get(body.getUUID());
        boolean episode = cur != null && cur.drowning;
        if (!submerged) {                            // out of the water → it will re-oxygenate; end any episode
            if (episode) cur.drowning = false;
            return;
        }
        if (!episode && air >= DROWN_AIR_TRIGGER) return;   // 100..280 and not yet engaged → wait until it's urgent

        ExtraState ex = extra(body);
        if (!ex.drowning) {
            ex.drowning = true;
            ex.drownRetargetAt = 0;
            ex.drownTarget = null;
            emit(body, "溺水反射:上浮逃生(气量 " + air + "/" + body.getMaxAirSupply() + ")。");
        }
        driveSwimUp(body, level, now, ex);
        ex.envMoveUntil = now;                       // claim movement: creeper + critical yield this tick
    }

    /** Stroke upward every tick; if a solid ceiling blocks the straight-up route, steer toward the nearest air column. */
    private static void driveSwimUp(NumenPlayer body, ServerLevel level, long now, ExtraState ex) {
        if (ceilingBlocked(body, level)) {
            if (now >= ex.drownRetargetAt) {         // refresh the (bounded) air-column search on a throttle
                ex.drownTarget = nearestAirColumn(body, level);
                ex.drownRetargetAt = now + DROWN_RETARGET_PERIOD;
            }
            if (ex.drownTarget != null) {
                InputDriver.stepToward(body, ex.drownTarget, false);   // swim horizontally toward open air (no sprint underwater)
            } else {
                InputDriver.halt(body);              // no reachable air found — hold horizontal, still stroke up
            }
        } else {
            InputDriver.halt(body);                  // clear route up → zero horizontal drift, pure ascent
        }
        InputDriver.jump(body);                      // the actual 上浮: per-tick swim-up stroke (+0.04/tick in water)
    }

    /** True if a non-fluid solid block caps the column just above the head (within {@link #DROWN_CEILING_LOOKUP}). */
    private static boolean ceilingBlocked(NumenPlayer body, ServerLevel level) {
        BlockPos head = body.blockPosition().above();
        for (int dy = 1; dy <= DROWN_CEILING_LOOKUP; dy++) {
            BlockState s = level.getBlockState(head.above(dy));
            if (!s.isAir() && s.getFluidState().isEmpty()) return true;   // solid (non-water) cap → can't just rise
        }
        return false;
    }

    /**
     * The nearest breathable air block within {@link #DROWN_CEILING_SCAN_RADIUS}, scanned ring by ring (nearest
     * first) over a shallow vertical band at/above the body, returned as a horizontal steering point at the body's
     * Y. Null if none is reachable in range (then the reflex just keeps stroking up in place). Only runs under a
     * ceiling, throttled by {@link #DROWN_RETARGET_PERIOD}.
     */
    private static Vec3 nearestAirColumn(NumenPlayer body, ServerLevel level) {
        BlockPos base = body.blockPosition();
        int by = base.getY();
        for (int r = 1; r <= DROWN_CEILING_SCAN_RADIUS; r++) {
            BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;   // ring shell only (Chebyshev == r)
                    int x = base.getX() + dx, z = base.getZ() + dz;
                    for (int y = by; y <= by + 4; y++) {                       // a surface within a short climb
                        if (level.getBlockState(new BlockPos(x, y, z)).isAir()) {
                            double d = (double) dx * dx + (double) dz * dz;
                            if (d < bestD) { bestD = d; best = new BlockPos(x, y, z); }
                            break;                                            // nearest air in this column → next column
                        }
                    }
                }
            }
            if (best != null) return new Vec3(best.getX() + 0.5, body.getY(), best.getZ() + 0.5);
        }
        return null;
    }

    // ============================================================ reflex L0b: fire / lava retreat

    /**
     * The heat death (the井底 lava accident). Trigger: {@code isOnFire()}, {@code isInLava()}, or lava in a
     * cardinal neighbour of the feet ("紧邻岩浆" preemption). Response: dash toward the nearest water within
     * {@link #HEAT_SCAN_RADIUS} to extinguish, else directly away from the nearest lava/fire block. While the body
     * is actively burning (on fire / in lava) the escape window is refreshed every tick so a debounce can never
     * strand it in the lava; a purely preemptive adjacency bout runs a fixed {@link #HEAT_FLEE_MAX_TICKS} window and
     * is debounced by {@link #HEAT_COOLDOWN} against flicker on a lava lip. One note per episode. Yields movement
     * to a live drowning episode; claims it over the creeper and critical reflexes.
     */
    private static void fireLavaReflex(NumenPlayer body, PerceptionState st, long now, ServerLevel level) {
        if (envOwnsMovement(body, now)) return;      // drowning (higher priority) owns movement this tick

        boolean onFire = body.isOnFire();
        boolean inLava = body.isInLava();
        boolean burning = onFire || inLava;          // actively taking heat damage right now
        ExtraState cur = EXTRA.get(body.getUUID());
        boolean bout = cur != null && now < cur.heatFleeUntil;

        if (!burning && !bout) {
            // Not burning, no live bout: preempt only if lava is adjacent AND the anti-flicker debounce has elapsed.
            if (!adjacentLava(body, level)) {
                if (cur != null) { cur.heatEpisode = false; cur.heatFleeUntil = 0; }   // clear of heat → reset the episode
                return;
            }
            if (cur != null && now < cur.heatCooldownUntil) return;
        }

        ExtraState ex = extra(body);
        if (burning || !bout) {                      // (re)open the escape window: continuously while burning, once for a preempt
            ex.heatFleeUntil = now + HEAT_FLEE_MAX_TICKS;
            ex.heatCooldownUntil = now + HEAT_FLEE_MAX_TICKS + HEAT_COOLDOWN;
        }
        if (!ex.heatEpisode) {
            ex.heatEpisode = true;
            emit(body, "火/岩浆退避:紧急脱离热源" + (inLava ? "(身处岩浆)" : onFire ? "(着火)" : "(紧邻岩浆)") + "。");
        }

        Vec3 dir = heatEscapeDir(body, level);       // toward water if any, else away from the nearest hazard block
        if (dir != null) {
            Vec3 waypoint = body.position().add(dir.scale(FLEE_LOOKAHEAD));
            InputDriver.stepToward(body, waypoint, true);
            if (body.horizontalCollision) InputDriver.jump(body);   // hop a lip that blocks the run
            ex.envMoveUntil = now;                   // claim movement: creeper + critical yield this tick
        } else if (!burning) {
            InputDriver.halt(body);                  // clear and nothing to steer by → let the bout lapse
            ex.heatFleeUntil = 0;
        }
    }

    /** True if lava sits in any of the four cardinal neighbours of the feet block (cheap "紧邻岩浆" probe). */
    private static boolean adjacentLava(NumenPlayer body, ServerLevel level) {
        BlockPos f = body.blockPosition();
        return isLava(level, f.offset(1, 0, 0)) || isLava(level, f.offset(-1, 0, 0))
                || isLava(level, f.offset(0, 0, 1)) || isLava(level, f.offset(0, 0, -1));
    }

    private static boolean isLava(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).getFluidState().is(FluidTags.LAVA);
    }

    /**
     * The heat-escape unit vector (horizontal): toward the nearest water block within {@link #HEAT_SCAN_RADIUS}
     * (extinguish + escape), else directly away from the nearest lava/fire/magma block. Null if neither is found
     * or the vector degenerates. Runs only inside a live heat bout.
     */
    private static Vec3 heatEscapeDir(NumenPlayer body, ServerLevel level) {
        BlockPos base = body.blockPosition();
        BlockPos water = null, hazard = null;
        double bestWater = Double.MAX_VALUE, bestHazard = Double.MAX_VALUE;
        int r = (int) Math.ceil(HEAT_SCAN_RADIUS);
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -2; dy <= 1; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = base.offset(dx, dy, dz);
                    double d = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                    BlockState s = level.getBlockState(p);
                    if (s.getFluidState().is(FluidTags.WATER)) {
                        if (d < bestWater) { bestWater = d; water = p; }
                    } else if (isHazardBlock(s)) {
                        if (d < bestHazard) { bestHazard = d; hazard = p; }
                    }
                }
            }
        }
        if (water != null) return flatten(Vec3.atCenterOf(water).subtract(body.position()));
        if (hazard != null) return flatten(body.position().subtract(Vec3.atCenterOf(hazard)));
        return null;
    }

    /** A lava fluid, a fire block, or a magma block — the heat sources the retreat runs away from. */
    private static boolean isHazardBlock(BlockState s) {
        if (s.getFluidState().is(FluidTags.LAVA)) return true;
        Block b = s.getBlock();
        return b == Blocks.FIRE || b == Blocks.SOUL_FIRE || b == Blocks.MAGMA_BLOCK;
    }

    /** Project {@code v} onto the horizontal plane and normalise; null if it degenerates to (near-)zero. */
    private static Vec3 flatten(Vec3 v) {
        Vec3 flat = new Vec3(v.x, 0.0, v.z);
        double len = flat.length();
        return len < 1.0E-4 ? null : flat.scale(1.0 / len);
    }

    // ============================================================ reflex L0c: auto-eat

    /**
     * Global survival auto-eat — the lowest-priority reflex. When the body is hungry ({@code foodLevel <=}
     * {@link #AUTOEAT_TRIGGER}) and nothing else is happening, hold the highest-nutrition ordinary food from the
     * pack and eat it via the native held use (the same {@code gameMode.useItem} + {@code isUsingItem} idiom
     * {@code EatCompanionTask} uses, distilled to a minimal loop), continuing until {@code foodLevel >=}
     * {@link #AUTOEAT_STOP}, then restoring the original hand (恢复原手持). Skips {@code canAlwaysEat} foods
     * (golden / enchanted apple, chorus fruit — precious / special). Any higher reflex episode or a recent hit
     * ({@link #AUTOEAT_HIT_GUARD}) immediately interrupts eating and restores the hand. One note per item eaten.
     */
    private static void autoEatReflex(NumenPlayer body, PerceptionState st, long now) {
        ExtraState ex = EXTRA.get(body.getUUID());
        boolean eating = ex != null && ex.eating;

        if (higherReflexBusy(body, st, now)) {       // any higher reflex or 受击 → never eat; abort a bite in progress
            if (eating) abortEat(body, ex);
            return;
        }

        int food = body.getFoodData().getFoodLevel();

        if (!eating) {
            if (food > AUTOEAT_TRIGGER) return;      // not hungry enough — cheapest bail (one int read)
            if (!st.ready("reflex_eat_fail", now)) return;   // backing off after a bite that couldn't be eaten
            int slot = bestFoodSlot(body);
            if (slot < 0) return;                    // no ordinary food in the pack
            ex = extra(body);
            ex.eating = true;
            ex.eatRestoreSlot = body.getInventory().getSelectedSlot();   // remember the hand to restore
            startBite(body, ex, slot);               // hold the food; the use fires next tick
            return;
        }

        if (food >= AUTOEAT_STOP) { finishEat(body, ex); return; }   // satisfied

        InputDriver.halt(body);                      // plant while chewing (the reflex owns the body briefly)
        if (!ex.eatStarted) {                        // fire the native held use for this bite
            ex.eatStarted = true;
            ex.eatBeforeFood = food;
            body.gameMode.useItem(body, body.level(), body.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
            return;
        }
        if (body.isUsingItem()) return;              // still chewing — keep planting (handled above)

        // The held use ended this tick.
        if (food > ex.eatBeforeFood) {               // an item was actually consumed
            emit(body, "自动进食:吃了 " + ex.eatLabel + "(饥饿 " + food + "/20)。");
            if (food < AUTOEAT_STOP) {
                int slot = bestFoodSlot(body);
                if (slot >= 0) { startBite(body, ex, slot); return; }   // still hungry, more food → next bite
            }
            finishEat(body, ex);
        } else {                                     // the bite didn't take (couldn't eat) → back off, don't loop
            st.arm("reflex_eat_fail", now, EAT_FAIL_COOLDOWN);
            finishEat(body, ex);
        }
    }

    /** Begin a bite: remember the food's label and equip it; the native use is fired on the following tick. */
    private static void startBite(NumenPlayer body, ExtraState ex, int slot) {
        ex.eatStarted = false;
        ex.eatLabel = Perceptions.safe(body.getInventory().getItem(slot).getHoverName().getString());
        body.holdInHand(slot);
    }

    /** End eating cleanly: stop any use, restore the original hand, plant, clear the episode. */
    private static void finishEat(NumenPlayer body, ExtraState ex) {
        if (body.isUsingItem()) body.stopUsingItem();
        if (ex.eatRestoreSlot >= 0) body.holdInHand(ex.eatRestoreSlot);
        InputDriver.halt(body);
        clearEat(ex);
    }

    /** Interrupted by a higher reflex: stop the use and restore the hand, but do NOT halt — the higher reflex drives. */
    private static void abortEat(NumenPlayer body, ExtraState ex) {
        if (body.isUsingItem()) body.stopUsingItem();
        if (ex.eatRestoreSlot >= 0) body.holdInHand(ex.eatRestoreSlot);
        clearEat(ex);
    }

    private static void clearEat(ExtraState ex) {
        ex.eating = false;
        ex.eatStarted = false;
        ex.eatRestoreSlot = -1;
        ex.eatLabel = "";
    }

    /**
     * The inventory slot of the highest-{@link FoodProperties#nutrition nutrition} ordinary food (tie-break on
     * saturation), or -1 if the pack holds none. Skips non-food items and {@link FoodProperties#canAlwaysEat}
     * foods (golden / enchanted apple, chorus fruit — the precious / special class the spec says to leave alone).
     */
    private static int bestFoodSlot(NumenPlayer body) {
        Inventory inv = body.getInventory();
        int best = -1, bestNutrition = 0;
        float bestSaturation = 0.0f;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            FoodProperties fp = s.get(DataComponents.FOOD);
            if (fp == null || fp.canAlwaysEat()) continue;   // not food, or a special always-edible (skip)
            int n = fp.nutrition();
            if (n > bestNutrition || (n == bestNutrition && fp.saturation() > bestSaturation)) {
                bestNutrition = n;
                bestSaturation = fp.saturation();
                best = i;
            }
        }
        return best;
    }

    /**
     * True when a higher-priority reflex owns the body, or the body was hit by a living attacker within
     * {@link #AUTOEAT_HIT_GUARD} (受击) — either case forbids / interrupts auto-eat.
     */
    private static boolean higherReflexBusy(NumenPlayer body, PerceptionState st, long now) {
        if (body.isInWall() || st.reflexSuffocateEpisode) return true;   // 窒息
        if (now < st.reflexCreeperFleeUntil) return true;                // 苦力怕 sprint
        if (st.reflexCriticalEpisode) return true;                       // 濒危战逃
        ExtraState ex = EXTRA.get(body.getUUID());
        if (ex != null && (ex.drowning || ex.heatEpisode)) return true;  // 溺水 / 火岩浆
        return st.reflexAttacker != null && (now - st.reflexAttackerSeenTick) <= AUTOEAT_HIT_GUARD;   // 受击
    }

    // ============================================================ shared flee mechanics

    /**
     * Sprint one tick along the flee vector, hopping any lip that blocks the run — the exact v1 primitive
     * ({@link InputDriver#stepToward} faces the yaw at a waypoint and pushes full-forward-with-sprint).
     * Shared by the critical-HP flee and the creeper panic sprint.
     */
    private static void driveFlee(NumenPlayer body, Entity threat) {
        Vec3 dir = fleeDirection(body, threat);
        Vec3 waypoint = body.position().add(dir.scale(FLEE_LOOKAHEAD));
        InputDriver.stepToward(body, waypoint, true);            // face the flee vector + sprint forward
        if (body.horizontalCollision) InputDriver.jump(body);   // hop a lip/step that blocks the run
    }

    /**
     * The flee unit vector. Default: directly away from {@code threat} (attacker → body). Protection
     * instinct (rule 4): when the owner is online, in the same dimension, within {@link #OWNER_FLEE_RANGE},
     * AND on the far side of the threat (dir-to-owner · dir-to-threat &lt; 0 — running to the owner is also
     * running away), flee TOWARD the owner instead.
     */
    private static Vec3 fleeDirection(NumenPlayer body, Entity threat) {
        Vec3 away = body.position().subtract(threat.position()).normalize();   // dir: threat → body
        ServerPlayer owner = body.resolveOwnerPlayer();
        if (owner != null && owner.level() == body.level() && body.distanceTo(owner) <= OWNER_FLEE_RANGE) {
            Vec3 toOwner = owner.position().subtract(body.position()).normalize();
            Vec3 toThreat = threat.position().subtract(body.position()).normalize();
            if (toOwner.dot(toThreat) < 0.0) return toOwner;   // owner opposite the threat → flee toward owner
        }
        return away;
    }

    // ============================================================ emit helpers

    /** A reflex note the brain is simply told about (kind="reflex"), non-urgent. */
    private static void emit(NumenPlayer body, String inner) {
        Companions.emitEvent(body, "<event kind=\"reflex\">" + inner + "</event>", false);
    }

    /** A reflex note tagged {@code provenance="observed"} (a directly-sensed body event), non-urgent. */
    private static void emitObserved(NumenPlayer body, String inner) {
        Companions.emitEvent(body, "<event kind=\"reflex\" provenance=\"observed\">" + inner + "</event>", false);
    }
}
