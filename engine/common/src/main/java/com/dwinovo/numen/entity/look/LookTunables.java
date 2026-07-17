package com.dwinovo.numen.entity.look;

/**
 * Every tunable knob of the lively-look engine, in ONE place, so it can be dialled
 * against live play. All values are the 26.1.2 research "20 tps draft" starting
 * points — <b>playtest-and-adjust</b>; nothing here is load-bearing for correctness
 * (the safety rails — 50° head/body cap, 1.4° quantization deadzone, no-overshoot —
 * are enforced in {@link LookMath} regardless of what these are set to).
 *
 * <h2>Reading the angular units</h2>
 * All rates are <b>degrees per server tick</b> at 20 tps: {@code 2°/tick = 40°/s},
 * {@code 4°/tick = 80°/s}, {@code 6°/tick = 120°/s}. The wire quantizes to
 * 1.40625°/unit and drops motion below ~0.7°/tick.
 */
public final class LookTunables {

    private LookTunables() {}

    // ---------------------------------------------------------------- head/body safety rails
    /**
     * Hard cap on |headYaw − bodyYaw| (deg). A fake player has NO vanilla clamp, so this is the
     * only owl-neck guard. Vanilla player value is 50°; do not raise past it or the client's own
     * body re-derivation disagrees with ours and the head visibly snaps. (26.1.2 study §A1/§A4.)
     */
    public static final float MAX_HEAD_REL_DEG = 50.0f;

    /**
     * Below this |target − body| offset the body stays put and only the head turns ("head leads,
     * body follows"); above it the body eases after the head. 30° matches the research's
     * "body follows past ~30–45°" (§C2). Kept comfortably under {@link #MAX_HEAD_REL_DEG} so the
     * body starts catching up BEFORE the head pins against the 50° rail.
     */
    public static final float BODY_FOLLOW_THRESHOLD_DEG = 30.0f;

    /** Stop-and-hold half-width (deg) for every axis — sized just over the 1.40625° wire unit so
     *  we never emit sub-quantization micro-jitter. (§C3 "±2–3° deadzone".) */
    public static final float DEADZONE_DEG = 2.5f;

    // ---------------------------------------------------------------- head (yaw) smoothing
    /**
     * Head yaw angular-velocity CAP (deg/tick). This is the anti-owl ceiling, NOT the normal
     * speed: it is set high enough (120°/s) that the exponential {@link #HEAD_K ease} — not the
     * cap — is what shapes a typical glance, which is exactly what keeps the motion organic
     * instead of constant-velocity. A big re-orientation to a target behind the body rides this
     * cap for its fast phase, then eases out over the last stretch. Well under combat snap speed.
     */
    public static final float HEAD_OMEGA_DEG = 6.0f;
    /** Head yaw ease factor: fraction of the remaining arc taken per tick (exponential approach). */
    public static final float HEAD_K = 0.25f;
    /** Minimum non-zero head step (deg/tick) — keeps a moving head above the ~0.7°/tick wire floor
     *  so it never slow-crawls below the quantizer instead of easing cleanly to the deadzone. */
    public static final float HEAD_MIN_STEP_DEG = 1.5f;

    // ---------------------------------------------------------------- body (yaw) idle follow
    /** Idle body-yaw follow cap (deg/tick). Only used when IDLE (no path owns the body). The client
     *  re-eases the rendered torso on top of this, so no floor is needed here. (§C table "≤4°/tick".) */
    public static final float BODY_OMEGA_DEG = 4.0f;
    /** Idle body-yaw ease factor (matches vanilla {@code tickHeadTurn}'s 0.3 body constant). */
    public static final float BODY_K = 0.3f;

    // ---------------------------------------------------------------- pitch (xRot) smoothing
    /** Pitch angular-velocity cap (deg/tick). */
    public static final float PITCH_OMEGA_DEG = 4.0f;
    /** Pitch ease factor. */
    public static final float PITCH_K = 0.3f;
    /** Natural pitch clamp (deg). Players can technically look ±90°, but staring straight up/down
     *  reads as broken; keep gaze within a natural cone. */
    public static final float MAX_PITCH_DEG = 60.0f;

    // ---------------------------------------------------------------- regime latch holds (ticks)
    /**
     * After a hard-aim write ({@code InputDriver.lookAt} — tools, combat, reflex fight-back), the
     * controller yields the head for this many ticks so brief gaps between a task's per-tick aims
     * don't flicker the head back to idle attention. (Priority ladder: hard-aim outranks all.)
     */
    public static final int HARD_AIM_HOLD_TICKS = 3;
    /**
     * After a locomotion write ({@code InputDriver.stepToward} — pathing, flee), the controller
     * treats the body yaw as externally owned for this many ticks and drives ONLY the head
     * (borrowing ±50° of the travel yaw). Sized to bridge the phase-2→phase-1 tick handoff.
     */
    public static final int BODY_CONTROL_HOLD_TICKS = 2;
    /** A look intent from the brain is considered stale after this many ticks with no refresh, after
     *  which the head relaxes back toward the body. The brain refreshes every tick it wants a gaze. */
    public static final int INTENT_TTL_TICKS = 5;
    /**
     * After a GUI-engagement write ({@code markEngaged} — the tool pack marks this every tick a
     * container menu is open: a station's GUI, a villager trade), the controller holds the engaged
     * posture — squared up to the target, body planted — for this many ticks past the last refresh.
     * A short latch so a one-tick gap (the LLM thinking between tool calls, a dispatcher hiccup)
     * can't drop the pose mid-session; the moment the menu truly closes the pack stops refreshing
     * and attention naturally returns within this window. (Priority ladder: engaged outranks idle
     * attention and locomotion, and yields only to a hard-aim snap.)
     */
    public static final int ENGAGED_HOLD_TICKS = 3;
}
