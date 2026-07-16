package com.dwinovo.numen.core.perception;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.pathing.exec.InputDriver;
import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.entity.PerceptionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

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
    /** How far ahead of the body the flee waypoint is placed (direction matters, not the exact point). */
    private static final double FLEE_LOOKAHEAD = 8.0;

    // ---- Reflex 2 (v2): heavy-hitter override ----
    /**
     * A single hit ≥ this fraction of max HP marks the attacker a "heavy hitter": flee unconditionally
     * (any distance, any HP), never trade blows. The iron-golem death — 7.5–18.5 dmg/hit is ≥25% of a
     * 20-HP body, so v1's distance-only rule kept swinging into a fatal exchange.
     */
    private static final float HEAVY_HIT_FRAC = 0.25f;

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
        Constants.LOG.info("[numen-core] reflex layer (spinal cord) registered");
    }

    /**
     * The per-tick reflex pass for one live companion. Called every tick from
     * {@link Perceptions#tickOne} (before the throttled perception polls, so survival acts first).
     */
    static void tick(NumenPlayer body, PerceptionState st, long now) {
        if (!ENABLED) return;
        if (body.isDeadOrDying()) return;   // the corpse is handled by NumenPlayer#tick / Companions#onDeath
        if (body.level() instanceof ServerLevel level) {
            suffocationReflex(body, st, now, level);
            creeperReflex(body, st, now, level);   // pre-explosion panic sprint (movement priority over the critical reflex)
        }
        criticalHpReflex(body, st, now);
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
     * The mauled-while-thinking death, hardened by v2's heavy-hitter seam. Engages when a LIVING attacker
     * has recently hit the body AND either its last blow alone was ≥ {@link #HEAVY_HIT_FRAC} of max HP
     * (rule 1 — engage at any HP) or HP is below {@link #HP_CRITICAL_FRAC} (rule 2). While engaged, each
     * tick: a HEAVY hitter is fled from unconditionally at any distance (the iron-golem death — trading
     * blows was fatal); a small hitter keeps the v1 rule — fight back (face + native swing, one per
     * {@link #SWING_COOLDOWN} at full charge) within {@link #MELEE_RANGE}, else flee. Flight follows the
     * shared owner-aware {@link #fleeDirection} vector for up to {@link #FLEE_MAX_TICKS}. A small-hitter
     * episode ends when HP recovers above {@link #HP_RECOVER_FRAC}; a heavy-hitter episode ends only when
     * the attacker is dead / gone for {@link #ATTACKER_GONE_TICKS} (an HP rebound is not safety — one more
     * of its blows can still be lethal). Yields movement to a live creeper sprint. One non-urgent note per
     * episode (the reflex has already reacted; it does not consult the brain).
     */
    private static void criticalHpReflex(NumenPlayer body, PerceptionState st, long now) {
        Entity attacker = st.reflexAttacker;
        boolean attackerLive = attacker != null && attacker.isAlive() && !attacker.isRemoved()
                && attacker.level() == body.level();
        float max = body.getMaxHealth();
        float hp = body.getHealth();
        boolean heavyHit = st.reflexLastHitDamage >= max * HEAVY_HIT_FRAC;   // v2: the last blow alone was ≥25% max HP

        if (st.reflexCriticalEpisode) {
            boolean gone = !attackerLive || (now - st.reflexAttackerSeenTick) > ATTACKER_GONE_TICKS;
            // A heavy hitter keeps us fleeing until it is gone — one of its blows can still be lethal, so an
            // HP rebound is not safety. A small hitter ends on HP recovery, exactly like v1.
            boolean recovered = !heavyHit && hp > max * HP_RECOVER_FRAC;
            if (gone || recovered) {
                if (st.reflexFleeUntil != 0) InputDriver.halt(body);   // stop the flee drive we owned
                endCriticalEpisode(st);
                return;
            }
            actCritical(body, st, now, attacker);
            return;
        }

        // Not engaged. Start on EITHER a heavy hit at any HP (rule 1) or a recent hit while HP is critical
        // (rule 2), from a still-live attacker.
        if (attackerLive && (now - st.reflexAttackerSeenTick) <= ATTACKER_GONE_TICKS
                && (heavyHit || hp < max * HP_CRITICAL_FRAC)) {
            st.reflexCriticalEpisode = true;
            st.reflexFleeUntil = 0;
            emit(body, "本能反应:生命垂危,已本能反击/撤离(攻击者:"
                    + Perceptions.safe(attacker.getName().getString()) + ")。");
            actCritical(body, st, now, attacker);
        }
    }

    /**
     * One tick of the engaged behaviour. Small hitter within melee reach → fight back (v1). Heavy hitter
     * at ANY distance, or a small hitter out of reach → flee. Yields movement to a live creeper sprint,
     * which outranks a melee exchange (an explosion is instant death).
     */
    private static void actCritical(NumenPlayer body, PerceptionState st, long now, Entity attacker) {
        if (now < st.reflexCreeperFleeUntil) return;   // creeper panic sprint owns movement this tick

        double dist = body.distanceTo(attacker);
        // Keep "last seen" fresh while the attacker is still nearby so the gone-timer only runs once it leaves.
        if (dist <= TRACK_RADIUS) st.reflexAttackerSeenTick = now;

        boolean heavyHit = st.reflexLastHitDamage >= body.getMaxHealth() * HEAVY_HIT_FRAC;
        if (!heavyHit && dist <= MELEE_RANGE) {
            fightBack(body, st, now, attacker);
        } else {
            flee(body, st, now, attacker);
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
     * Sprint away from the attacker along the flee vector ({@link #driveFlee}), for at most
     * {@link #FLEE_MAX_TICKS} per bout so the body doesn't sprint off a cliff forever.
     */
    private static void flee(NumenPlayer body, PerceptionState st, long now, Entity attacker) {
        if (st.reflexFleeUntil == 0) st.reflexFleeUntil = now + FLEE_MAX_TICKS;
        if (now < st.reflexFleeUntil) {
            driveFlee(body, attacker);
        } else {
            InputDriver.halt(body);   // bout spent — stop driving movement, let the brain take over
        }
    }

    private static void endCriticalEpisode(PerceptionState st) {
        st.reflexCriticalEpisode = false;
        st.reflexFleeUntil = 0;
        st.reflexAttacker = null;       // don't pin a possibly-removed entity; the next hit re-arms it
        st.reflexLastHitDamage = 0.0f;  // clear the heavy-hitter latch with the attacker
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
                driveFlee(body, creeper);          // bout live → sprint away (movement priority over the critical reflex)
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
