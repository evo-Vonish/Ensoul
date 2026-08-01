package com.dwinovo.numen.core.look;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.exec.InputDriver;
import com.dwinovo.numen.core.perception.Perceptions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.RandomSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The companion's <strong>attention brain</strong>: the zero-LLM policy that decides
 * <em>what</em> a companion looks at, moment to moment, so its gaze feels alive —
 * mostly its owner, with glances at nearby animals and idle look-arounds — never a
 * dead-eyed stare. It produces a single "gaze intent" (a world point) each tick and
 * hands it to the body's look engine ({@link InputDriver#setLookIntent}); the engine
 * ({@code numen-api}'s {@code LookController}) does the smooth, owl-safe kinematics.
 *
 * <h2>Division of labour</h2>
 * <ul>
 *   <li><b>Brain (here, pack):</b> interest scoring, dwell, habituation, switch cooldown —
 *       "look at the owner / that sheep / around". Runs in the end-of-server-tick dispatch.</li>
 *   <li><b>Engine ({@code LookController}, api):</b> turns the chosen point into eased head/body
 *       motion within the safety rails. Runs at the tail of the body's own tick.</li>
 * </ul>
 * The brain never writes rotation directly and never fights hard-aim / locomotion — those yield
 * automatically through {@code InputDriver} (the engine ignores a stale idle intent while a fresher
 * hard-aim or a mover owns the body), so the brain can push its intent every tick unconditionally.
 *
 * <h2>Free-riding perception (26.1.2 study §B3)</h2>
 * The owner anchor is resolved for free via {@link NumenPlayer#resolveOwnerPlayer()} (a cross-dim
 * player-table lookup, near-zero cost). Nearby animals are NOT cached anywhere, so we run our own
 * light throttled scan ({@link #SCAN_PERIOD} ticks, small radius) — exactly the option the study
 * endorses. Threat glances (creeper / recently-seen attacker) ARE scored here now, as a high-weight
 * interest point: the point comes free from the perception layer via
 * {@link Perceptions#activeThreatEyePos(NumenPlayer)} — a read-only peek at the threat the reflex layer
 * already tracks (creeper first, then a recent attacker). It scores just below the owner ({@link #W_THREAT}),
 * so danger reliably pulls the gaze into a panicked look-back while the companion flees. During an actual
 * melee swing the reflex layer hard-aims the head at the target and this idle intent simply yields (the engine
 * ignores a stale idle intent while a hard-aim or a mover owns the body), so the two never fight.
 *
 * <h2>Tuning</h2>
 * Every number below is a 26.1.2 "20 tps draft" starting point — <b>playtest-and-adjust</b>.
 */
public final class LookBrain {

    private LookBrain() {}

    // ------------------------------------------------------------------ interest weights (§C1)
    /** Owner social bias — dominant. Scaled into [0.5,1.0] by proximity so the owner, when present, usually wins. */
    private static final double W_OWNER = 1.0;
    /**
     * Active-threat glance — the panicked look-back at a creeper / recent attacker the reflex layer is tracking.
     * Deliberately high (below the owner's 1.0 max) so danger reliably wins the gaze during a flee, yet a
     * point-blank owner still edges it. Fixed (not proximity-scaled): the whole point is a dependable look-back.
     */
    private static final double W_THREAT = 0.9;
    /** Base weight of a nearby animal (scaled by proximity). */
    private static final double W_ANIMAL = 0.4;
    /** Extra weight for a MOVING animal (relative motion draws the eye). */
    private static final double W_MOTION = 0.6;
    /** Idle "look around" fallback weight — always available, lowest. */
    private static final double W_ENVIRONMENT = 0.2;

    // ------------------------------------------------------------------ ranges
    /** Look at the owner only within this distance (blocks) and same dimension. */
    private static final double OWNER_MAX_DIST = 32.0;
    /** Animal interest radius (blocks) — the small throttled scan box. */
    private static final double ANIMAL_MAX_DIST = 12.0;
    /** Animal speed (blocks/tick) that saturates the motion bonus. */
    private static final double ANIMAL_MOTION_SATURATE = 0.15;

    // ------------------------------------------------------------------ dwell / cooldown / habituation
    /** Owner gaze hold: 40–80 ticks (2–4 s), matching vanilla LookAtPlayerGoal. */
    private static final int DWELL_OWNER_MIN = 40, DWELL_OWNER_MAX = 80;
    /** Animal / environment gaze hold: 20–40 ticks (1–2 s), matching RandomLookAroundGoal. */
    private static final int DWELL_OTHER_MIN = 20, DWELL_OTHER_MAX = 40;
    /** After a switch, don't switch again for this many ticks — anti-twitch. */
    private static final int SWITCH_COOLDOWN = 8;
    /**
     * Per-tick habituation growth on the CURRENT target (its gaze slowly "gets boring"). At 0.01/tick
     * a close owner (effective score ~1.0) is penalised below the environment floor (0.2) after ~80
     * ticks (~4 s) of staring, at which point the companion glances away; a distant owner loses interest
     * sooner. Reset to 0 the instant the gaze switches.
     */
    private static final double HABIT_RATE = 0.01;
    /** Habituation ceiling — set above the owner's max score so even a point-blank owner eventually
     *  yields a glance-away (owner max 1.0, floor env 0.2 → need &gt; 0.8). */
    private static final double HABIT_MAX = 0.85;

    // ------------------------------------------------------------------ scan
    /** Ticks between animal scans (the only entity query; every other tick is free). */
    private static final int SCAN_PERIOD = 15;

    // ------------------------------------------------------------------ environment glance geometry
    private static final double ENV_GLANCE_DIST = 10.0;
    private static final float ENV_YAW_SPREAD = 100.0f;   // ± deg from body yaw
    private static final float ENV_PITCH_MIN = -12.0f, ENV_PITCH_MAX = 18.0f;

    private enum Kind { OWNER, THREAT, ANIMAL, ENVIRONMENT }

    /** Per-companion attention state (keyed by companion UUID). Server-thread only — no sync. */
    private static final class State {
        Kind kind = Kind.ENVIRONMENT;
        int targetEntityId = -1;      // for ANIMAL
        long dwellUntil = Long.MIN_VALUE;
        long switchReady = Long.MIN_VALUE;
        double habituation = 0.0;
        long lastScanTick = Long.MIN_VALUE;
        List<Animal> nearbyAnimals = List.of();
        // active-threat glance point, refreshed read-only each tick from the perception layer; null = no live threat
        Vec3 threatPoint = null;
        // frozen environment glance point (held for the dwell, then repicked)
        double envX, envY, envZ;
        boolean envValid = false;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    /** Drop a companion's attention state (body removed / dismissed / dead). Idempotent. */
    public static void forget(UUID companionUuid) {
        STATES.remove(companionUuid);
    }

    /**
     * One attention tick for a live companion. Called per companion from the end-of-server-tick
     * dispatch. Scores the interest points, honours dwell / cooldown / habituation, and pushes the
     * chosen gaze point as a smooth intent. Cheap: at most one throttled animal scan every
     * {@link #SCAN_PERIOD} ticks, otherwise pure arithmetic.
     */
    public static void tick(NumenPlayer body) {
        if (body.isDeadOrDying() || body.isRemoved()) {
            return;
        }
        long now = body.level().getGameTime();
        State st = STATES.computeIfAbsent(body.getUUID(), k -> new State());

        // Refresh the throttled animal scan.
        if (now - st.lastScanTick >= SCAN_PERIOD) {
            st.lastScanTick = now;
            st.nearbyAnimals = scanAnimals(body);
        }

        // Resolve the owner anchor (free).
        ServerPlayer owner = body.resolveOwnerPlayer();
        boolean ownerEligible = owner != null
                && owner.level() == body.level()
                && body.distanceTo(owner) <= OWNER_MAX_DIST;

        // Score the best animal candidate (if any).
        Animal bestAnimal = null;
        double bestAnimalScore = -1.0;
        for (Animal a : st.nearbyAnimals) {
            if (a == null || !a.isAlive() || a.isRemoved()) continue;
            double d = body.distanceTo(a);
            if (d > ANIMAL_MAX_DIST) continue;
            double prox = 1.0 - (d / ANIMAL_MAX_DIST);            // [0,1]
            double motion = Math.min(1.0, a.getDeltaMovement().length() / ANIMAL_MOTION_SATURATE);
            double score = W_ANIMAL * prox + W_MOTION * motion * prox;
            if (score > bestAnimalScore) {
                bestAnimalScore = score;
                bestAnimal = a;
            }
        }

        double ownerScore = ownerEligible
                ? W_OWNER * (0.5 + 0.5 * (1.0 - body.distanceTo(owner) / OWNER_MAX_DIST))
                : -1.0;

        // Active-threat glance point (creeper / recent attacker) — a free read-only peek at the reflex layer's
        // tracked threat. Null on the common no-threat path. Refreshed every tick (like the owner anchor) and
        // stashed for resolvePoint / validity; the reflex layer's hard-aim during a melee swing takes the head
        // regardless, so this intent only actually steers the gaze while fleeing (see the class note).
        Vec3 threatPoint = Perceptions.activeThreatEyePos(body);
        st.threatPoint = threatPoint;
        boolean threatEligible = threatPoint != null;
        double threatScore = threatEligible ? W_THREAT : -1.0;

        double envScore = W_ENVIRONMENT;   // always available as the idle-glance floor

        // Grow habituation on the current gaze (a stare slowly "gets boring"); it resets on a switch.
        boolean currentValid = currentTargetValid(st, ownerEligible, threatEligible);
        st.habituation = currentValid ? Math.min(HABIT_MAX, st.habituation + HABIT_RATE) : 0.0;

        // Effective scores: ONLY the current target carries its habituation penalty, so a long stare
        // eventually falls below a fresh rival — that (not a raw-score compare) is what makes the
        // companion glance away from an otherwise dominant owner.
        double h = st.habituation;
        boolean curAnimal = st.kind == Kind.ANIMAL && bestAnimal != null && bestAnimal.getId() == st.targetEntityId;
        double ownerEff  = ownerScore      - (st.kind == Kind.OWNER ? h : 0.0);
        double threatEff = threatScore     - (st.kind == Kind.THREAT ? h : 0.0);
        double animalEff = bestAnimalScore - (curAnimal ? h : 0.0);
        double envEff    = envScore        - (st.kind == Kind.ENVIRONMENT ? h : 0.0);

        // Best rival by EFFECTIVE score (environment is the ever-present floor). Threat is compared LAST so that,
        // at an exact tie, danger wins the gaze (its 0.9 already tops animals / environment and usually the owner).
        Kind bestKind = Kind.ENVIRONMENT;
        double bestEff = envEff;
        int bestId = -1;
        if (bestAnimal != null && animalEff > bestEff) { bestKind = Kind.ANIMAL; bestEff = animalEff; bestId = bestAnimal.getId(); }
        if (ownerEligible && ownerEff > bestEff)       { bestKind = Kind.OWNER;  bestEff = ownerEff;  bestId = -1; }
        if (threatEligible && threatEff >= bestEff)    { bestKind = Kind.THREAT; bestEff = threatEff; bestId = -1; }

        boolean isCurrent = currentValid && bestKind == st.kind
                && (bestKind != Kind.ANIMAL || bestId == st.targetEntityId);
        boolean dwellExpired = now >= st.dwellUntil;
        boolean cooldownOk = now >= st.switchReady;

        // Hold a chosen gaze for its full dwell (like vanilla LookAt/RandomLookAround goals), then, once
        // the anti-twitch cooldown has also elapsed, re-home onto the best effective rival. An invalid
        // current target (owner left / animal gone) forces an immediate re-home regardless of dwell.
        if (!currentValid || (dwellExpired && cooldownOk && !isCurrent)) {
            switchTo(st, body, now, bestKind, bestId);
        }

        // Emit the gaze point for the (possibly just-chosen) current target.
        Vec3 point = resolvePoint(st, owner);
        if (point != null) {
            InputDriver.setLookIntent(body, point);
        }
    }

    private static boolean currentTargetValid(State st, boolean ownerEligible, boolean threatEligible) {
        switch (st.kind) {
            case OWNER:       return ownerEligible;
            case THREAT:      return threatEligible;            // threat gone ⇒ re-home immediately (like the owner leaving)
            case ANIMAL:      return animalStillPresent(st);   // dropped from the scan box ⇒ stop staring
            case ENVIRONMENT: return st.envValid;
            default:          return false;
        }
    }

    private static boolean animalStillPresent(State st) {
        for (Animal a : st.nearbyAnimals) {
            if (a != null && a.getId() == st.targetEntityId && a.isAlive() && !a.isRemoved()) return true;
        }
        return false;
    }

    private static void switchTo(State st, NumenPlayer body, long now, Kind kind, int entityId) {
        st.kind = kind;
        st.targetEntityId = entityId;
        st.habituation = 0.0;
        st.switchReady = now + SWITCH_COOLDOWN;
        int min = kind == Kind.OWNER ? DWELL_OWNER_MIN : DWELL_OTHER_MIN;
        int max = kind == Kind.OWNER ? DWELL_OWNER_MAX : DWELL_OTHER_MAX;
        RandomSource rng = body.getRandom();
        st.dwellUntil = now + min + rng.nextInt(max - min + 1);
        if (kind == Kind.ENVIRONMENT) {
            pickEnvironmentGlance(st, body, rng);
        } else {
            st.envValid = false;
        }
    }

    /** Freeze a random glance point around the body (held for this environment dwell). */
    private static void pickEnvironmentGlance(State st, NumenPlayer body, RandomSource rng) {
        float yaw = body.getYRot() + (rng.nextFloat() * 2.0f - 1.0f) * ENV_YAW_SPREAD;
        float pitch = ENV_PITCH_MIN + rng.nextFloat() * (ENV_PITCH_MAX - ENV_PITCH_MIN);
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double horiz = Math.cos(pitchRad) * ENV_GLANCE_DIST;
        Vec3 eye = body.getEyePosition();
        st.envX = eye.x - Math.sin(yawRad) * horiz;
        st.envY = eye.y - Math.sin(pitchRad) * ENV_GLANCE_DIST;
        st.envZ = eye.z + Math.cos(yawRad) * horiz;
        st.envValid = true;
    }

    private static Vec3 resolvePoint(State st, ServerPlayer owner) {
        switch (st.kind) {
            case OWNER:
                return owner != null ? owner.getEyePosition() : null;
            case THREAT:
                return st.threatPoint;   // resolved read-only at the top of this tick; null re-homes via currentTargetValid
            case ANIMAL:
                LivingEntity a = findAnimal(st);
                return a != null ? a.getEyePosition() : null;
            case ENVIRONMENT:
                return st.envValid ? new Vec3(st.envX, st.envY, st.envZ) : null;
            default:
                return null;
        }
    }

    private static Animal findAnimal(State st) {
        for (Animal a : st.nearbyAnimals) {
            if (a != null && a.getId() == st.targetEntityId && a.isAlive() && !a.isRemoved()) return a;
        }
        return null;
    }

    /** The sole entity query — nearby live animals within {@link #ANIMAL_MAX_DIST}, throttled to {@link #SCAN_PERIOD}. */
    private static List<Animal> scanAnimals(NumenPlayer body) {
        AABB box = body.getBoundingBox().inflate(ANIMAL_MAX_DIST);
        return body.level().getEntitiesOfClass(Animal.class, box,
                a -> a.isAlive() && !a.isRemoved());
    }
}
