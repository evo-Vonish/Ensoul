package com.dwinovo.numen.entity.look;

import net.minecraft.server.level.ServerPlayer;

/**
 * The companion's <strong>lively look engine</strong>: a per-tick, deterministic,
 * zero-LLM kinematic writer of the body's {@code yRot} / {@code yHeadRot} /
 * {@code xRot}. It turns a raw "gaze intent" (a world point the attention brain
 * wants looked at) into smooth, alive head-and-body motion, and it is the one place
 * that enforces the fake player's missing head/body safety rails.
 *
 * <h2>The single legal write window (26.1.2 study §A4/§B2)</h2>
 * A fake {@code ServerPlayer} has none of a Mob's look controllers, and worse:
 * {@code Player.aiStep} hard-writes {@code yHeadRot = yRot} every tick, then
 * {@code ServerEntity} ships the rotation to clients. The ONLY window where an
 * independent head survives to the wire is <em>after</em> {@code super.tick()}
 * (which runs aiStep) and <em>before</em> the end-of-tick chunk dispatch — i.e.
 * inside {@code NumenPlayer.tick()}. So {@link #tick()} is called there, last.
 * Writing the head anywhere in the end-of-server-tick phase (tasks / perception)
 * would just be clobbered by next tick's aiStep before it ever shipped.
 *
 * <h2>Priority ladder (who owns the body this tick)</h2>
 * Resolved purely from freshness latches, so callers never coordinate:
 * <ol>
 *   <li><b>Hard-aim</b> — {@code InputDriver.lookAt} (tools, combat, reflex fight-back) already
 *       snapped the whole aim this tick; the controller {@link #markHardAim() yields} entirely
 *       so the head stays glued to the aim.</li>
 *   <li><b>Body-controlled</b> — {@code InputDriver.stepToward} (pathing, flee) owns {@code yRot}
 *       as the travel direction; the controller drives ONLY the head (and pitch), borrowing ±50°
 *       of the travel yaw. See {@link #markBodyControlled()}.</li>
 *   <li><b>Idle attention</b> — nobody else owns the body; the controller drives head + pitch and
 *       lazily turns the body to follow ("head leads, body follows").</li>
 *   <li><b>Relax</b> — no intent at all; the head eases back to the body and the pitch levels.</li>
 * </ol>
 *
 * <p>All feel/limit numbers live in {@link LookTunables}; all angle math is the
 * unit-tested {@link LookMath}. This class only routes and applies.
 *
 * <p><b>Threading.</b> Server-thread only (constructed and ticked from the body's
 * server tick; intent set from the end-of-server-tick brain, also server thread), so
 * the fields need no synchronisation.
 */
public final class LookController {

    private final ServerPlayer body;

    // ---- gaze intent (a world point the brain wants looked at) ----
    private boolean hasIntent;
    private double tgtX, tgtY, tgtZ;
    private long intentTick = Long.MIN_VALUE;

    // ---- ownership latches (game-time of the last write of each kind) ----
    private long hardAimTick = Long.MIN_VALUE;
    private long bodyControlTick = Long.MIN_VALUE;

    public LookController(ServerPlayer body) {
        this.body = body;
    }

    private long now() {
        return body.level().getGameTime();
    }

    // ============================================================ intent / latch inputs

    /**
     * Set the desired gaze target — a world point (typically an entity's eye position). Called by
     * the attention brain every tick it wants a gaze; the intent goes stale on its own after
     * {@link LookTunables#INTENT_TTL_TICKS} if the brain stops refreshing it.
     */
    public void lookAt(double x, double y, double z) {
        this.tgtX = x;
        this.tgtY = y;
        this.tgtZ = z;
        this.hasIntent = true;
        this.intentTick = now();
    }

    /** Drop the gaze intent (the head will relax back toward the body). */
    public void clearIntent() {
        this.hasIntent = false;
    }

    /**
     * Latch: a hard-aim snap happened this tick (the caller already wrote the full aim via
     * {@code p.lookAt(...)}). The controller will yield the head for
     * {@link LookTunables#HARD_AIM_HOLD_TICKS} so the head stays on the aim.
     */
    public void markHardAim() {
        this.hardAimTick = now();
    }

    /**
     * Latch: locomotion owns the body yaw this tick ({@code stepToward} set {@code yRot} to the
     * travel direction). The controller drives only the head for
     * {@link LookTunables#BODY_CONTROL_HOLD_TICKS}, leaving {@code yRot} to the mover.
     */
    public void markBodyControlled() {
        this.bodyControlTick = now();
    }

    // ============================================================ per-tick kinematics

    /**
     * Advance the look one tick. MUST be called at the tail of {@code NumenPlayer.tick()} (after
     * {@code super.tick()} and {@code doTick()}), the only place an independent head survives to
     * the client. No-op'd naturally when a fresher hard-aim owns the body.
     */
    public void tick() {
        long now = now();
        boolean hardAim = now - hardAimTick <= LookTunables.HARD_AIM_HOLD_TICKS;
        if (hardAim) {
            // The aim (yRot/xRot/yHeadRot) is already snapped by the caller and re-affirmed by
            // aiStep (yHeadRot = yRot). Fully yield; the smoother resumes from this pose next tick.
            return;
        }

        boolean bodyControlled = now - bodyControlTick <= LookTunables.BODY_CONTROL_HOLD_TICKS;
        boolean intentLive = hasIntent && (now - intentTick) <= LookTunables.INTENT_TTL_TICKS;

        float bodyYaw = body.getYRot();
        float headYaw = body.getYHeadRot();
        float pitch = body.getXRot();

        if (!intentLive) {
            // Relax: only when idle (a mover owning yRot keeps aiStep's head-faces-travel default).
            if (!bodyControlled) {
                float relaxedHead = LookMath.approachAngle(headYaw, bodyYaw,
                        LookTunables.HEAD_OMEGA_DEG, LookTunables.HEAD_K,
                        LookTunables.DEADZONE_DEG, 0.0f);
                relaxedHead = LookMath.clampHeadToBody(relaxedHead, bodyYaw, LookTunables.MAX_HEAD_REL_DEG);
                body.setYHeadRot(relaxedHead);

                float leveled = LookMath.approachAngle(pitch, 0.0f,
                        LookTunables.PITCH_OMEGA_DEG, LookTunables.PITCH_K,
                        LookTunables.DEADZONE_DEG, 0.0f);
                body.setXRot(leveled);
            }
            return;
        }

        // Desired yaw/pitch from the gaze point, measured from the EYE.
        double dx = tgtX - body.getX();
        double dy = tgtY - body.getEyeY();
        double dz = tgtZ - body.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float desiredPitch = LookMath.clamp(
                (float) (-Math.toDegrees(Math.atan2(dy, horiz))),
                -LookTunables.MAX_PITCH_DEG, LookTunables.MAX_PITCH_DEG);

        if (bodyControlled) {
            // Locomotion owns yRot — drive the head only, clamped to the travel yaw ±50°.
            float newHead = LookMath.approachAngle(headYaw, desiredYaw,
                    LookTunables.HEAD_OMEGA_DEG, LookTunables.HEAD_K,
                    LookTunables.DEADZONE_DEG, LookTunables.HEAD_MIN_STEP_DEG);
            newHead = LookMath.clampHeadToBody(newHead, bodyYaw, LookTunables.MAX_HEAD_REL_DEG);
            body.setYHeadRot(newHead);
        } else {
            // Idle: head leads; body lazily follows once the offset is large enough. This mirrors
            // vanilla BodyRotationControl's "static head-lead / delayed body-follow" feel (§A2),
            // and — because the client re-eases the rendered torso — a modest body step is enough.
            float offset = LookMath.headBodyOffset(desiredYaw, bodyYaw);
            float newBody = bodyYaw;
            if (Math.abs(offset) > LookTunables.BODY_FOLLOW_THRESHOLD_DEG) {
                newBody = LookMath.approachAngle(bodyYaw, desiredYaw,
                        LookTunables.BODY_OMEGA_DEG, LookTunables.BODY_K,
                        LookTunables.DEADZONE_DEG, 0.0f);
                body.setYRot(newBody);
            }
            float newHead = LookMath.approachAngle(headYaw, desiredYaw,
                    LookTunables.HEAD_OMEGA_DEG, LookTunables.HEAD_K,
                    LookTunables.DEADZONE_DEG, LookTunables.HEAD_MIN_STEP_DEG);
            newHead = LookMath.clampHeadToBody(newHead, newBody, LookTunables.MAX_HEAD_REL_DEG);
            body.setYHeadRot(newHead);
        }

        float newPitch = LookMath.approachAngle(pitch, desiredPitch,
                LookTunables.PITCH_OMEGA_DEG, LookTunables.PITCH_K,
                LookTunables.DEADZONE_DEG, 0.0f);
        body.setXRot(newPitch);
    }
}
