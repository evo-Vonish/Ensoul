package com.dwinovo.numen.entity.look;

/**
 * Pure, dependency-free angular kinematics for the {@link LookController}. Every
 * method here is a static {@code float} function with <em>zero</em> Minecraft
 * imports, so the whole "does the smoothing feel alive and stay inside the safety
 * rails" contract can be unit-tested with a bare {@code javac} run (no game, no
 * Gradle) — see {@code LookMathTest} in the design scratchpad.
 *
 * <h2>Why these three primitives</h2>
 * The research (26.1.2 rotation study, §C2/§C3) fixes the feel rules:
 * <ul>
 *   <li><b>Never constant-velocity.</b> Constant angular speed reads as a machine.
 *       {@link #approachAngle} eases exponentially (step ∝ distance) so the head
 *       decelerates into its target the way a real neck does.</li>
 *   <li><b>Angular-velocity CAP, not throttle.</b> {@code omegaMax} is an anti-owl
 *       ceiling (a head can't whip around infinitely fast), NOT the normal speed —
 *       it must sit high enough that the exponential ease is what you actually see
 *       for typical glances, or you are back to constant-velocity at the cap.</li>
 *   <li><b>Quantization floor.</b> The wire quantizes yaw to 1.40625°/unit and drops
 *       sub-~0.7°/tick motion (26.1.2 study §A5). {@code minStep} keeps a moving axis
 *       above that floor so it never "slow-crawls" below the wire's resolution; the
 *       {@code deadzone} instead <em>stops</em> the axis once it is close enough, so
 *       we never emit the 0.6° micro-jitter that would thrash the byte quantizer.</li>
 *   <li><b>Head/body 50° self-limit.</b> A fake player has no vanilla
 *       {@code clampHeadRotationToBody} (that is Mob-only), so {@link #clampHeadToBody}
 *       is the ONLY thing standing between us and an owl-necked companion.</li>
 * </ul>
 *
 * <p>All angles are in degrees. Callers pass the live entity yaw/pitch and write the
 * result straight back onto the body — the entity float holds sub-quantization
 * precision between ticks, so an ease step below one wire-unit still accumulates.
 */
public final class LookMath {

    private LookMath() {}

    /** Wrap an angle to (-180, 180], matching {@code net.minecraft.util.Mth#wrapDegrees}. */
    public static float wrapDegrees(float deg) {
        float d = deg % 360.0f;
        if (d >= 180.0f) d -= 360.0f;
        if (d < -180.0f) d += 360.0f;
        return d;
    }

    public static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    /**
     * One tick of exponentially-eased, speed-capped, deadzoned rotation of {@code current}
     * toward {@code target}. The heart of the "alive, not mechanical" motion.
     *
     * <p>Algorithm (all on the shortest signed arc {@code delta = wrap(target-current)}):
     * <ol>
     *   <li><b>Deadzone.</b> {@code |delta| <= deadzone} → return {@code current} unchanged.
     *       This is what makes a target that is already "close enough" produce <em>no</em>
     *       motion, so we never jitter at the 1.4° wire-quantization noise floor.</li>
     *   <li><b>Ease.</b> The raw step is {@code k * delta} — proportional to remaining
     *       distance, i.e. an exponential approach that decelerates into the target.</li>
     *   <li><b>Speed cap.</b> Magnitude clamped to {@code omegaMax} (the anti-owl ceiling).</li>
     *   <li><b>Floor.</b> Magnitude raised to at least {@code minStep} so a moving axis
     *       stays above the wire resolution (skip by passing {@code minStep = 0}).</li>
     *   <li><b>No overshoot.</b> Magnitude finally clamped to {@code |delta|}, so the step
     *       can never cross the target — guaranteeing monotone convergence.</li>
     * </ol>
     *
     * @param current    live angle (deg); may be unbounded — only the wrapped delta matters
     * @param target     desired angle (deg)
     * @param omegaMax   max degrees moved this tick (angular-velocity cap); must be &gt; 0
     * @param k          ease factor in (0,1]; the fraction of the remaining arc taken as the raw step
     * @param deadzone   stop-and-hold half-width (deg); {@code >= 0}
     * @param minStep    minimum non-zero step magnitude (deg) to stay above wire quantization; {@code >= 0}
     * @return the new angle, wrapped to (-180,180]
     */
    public static float approachAngle(float current, float target,
                                      float omegaMax, float k,
                                      float deadzone, float minStep) {
        float delta = wrapDegrees(target - current);
        float ad = Math.abs(delta);
        if (ad <= deadzone) {
            return wrapDegrees(current);   // close enough — hold, no jitter
        }
        float mag = Math.abs(k * delta);   // exponential ease: step shrinks with distance
        if (mag > omegaMax) mag = omegaMax;   // anti-owl speed cap
        if (mag < minStep) mag = minStep;      // stay above the wire's quantization floor
        if (mag > ad) mag = ad;                // never overshoot the target
        float step = Math.signum(delta) * mag;
        return wrapDegrees(current + step);
    }

    /**
     * Clamp a head yaw to within {@code maxRel} degrees of the body yaw — the fake-player
     * substitute for vanilla's Mob-only {@code clampHeadRotationToBody}. Without this the
     * companion's head can point backwards (owl). Returns a head yaw whose signed arc from
     * {@code bodyYaw} is in {@code [-maxRel, +maxRel]}.
     */
    public static float clampHeadToBody(float headYaw, float bodyYaw, float maxRel) {
        float rel = wrapDegrees(headYaw - bodyYaw);
        if (rel > maxRel) rel = maxRel;
        else if (rel < -maxRel) rel = -maxRel;
        return wrapDegrees(bodyYaw + rel);
    }

    /**
     * The signed arc {@code head - body}, wrapped — i.e. how far the head is turned relative
     * to the torso. Handy for the "head leads, body follows" decision and for assertions.
     */
    public static float headBodyOffset(float headYaw, float bodyYaw) {
        return wrapDegrees(headYaw - bodyYaw);
    }
}
