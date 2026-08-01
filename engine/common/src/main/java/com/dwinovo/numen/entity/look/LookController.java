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
 *   <li><b>Engaged</b> — {@link #markEngaged the tool pack has a container menu open} (a station's
 *       GUI, a villager trade) and the companion must square up to the target and stay planted for
 *       the whole session. The controller eases the BODY yaw onto the target bearing and the head
 *       onto the exact point (naturally dipping the pitch to a block centre below the eyes), like a
 *       real player standing at a workbench — and holds it across the LLM's thinking gaps.</li>
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

    // ---- GUI engagement (a world point to square up to while a container menu is open) ----
    private long engagedTick = Long.MIN_VALUE;
    private double engX, engY, engZ;

    /**
     * The smoother's OWN persisted head yaw. {@code Player.aiStep} clobbers the entity's
     * {@code yHeadRot} back to {@code yRot} every tick (and locomotion's faceYaw re-affirms it),
     * so stepping from {@code body.getYHeadRot()} could never accumulate: each tick restarted
     * from "facing travel" and shipped a single ≤ω step — a dead gaze while walking (field
     * evidence: liveliness only visible when idle, where the BODY yaw accumulates instead).
     * Stepping from this persisted pose makes the glance actually progress across ticks;
     * invalidated whenever a hard-aim or a no-intent mover tick owns the real head, so the
     * smoother resumes from the true pose instead of a stale one.
     */
    private float smoothHead;
    private boolean smoothHeadValid;

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

    /**
     * Latch + target: the companion is engaged with a GUI (a station's menu, a villager trade) and
     * must face the world point {@code (x,y,z)} — a block centre or the trade partner's eyes — and
     * stay planted for the whole session. The tool pack refreshes this every tick the container menu
     * is open (and zeroes locomotion input itself); it auto-expires
     * {@link LookTunables#ENGAGED_HOLD_TICKS} ticks after the last refresh (menu closed / handed
     * off), after which attention naturally returns. Outranks idle attention and locomotion; yields
     * only to a hard-aim snap. See the priority ladder in the class doc.
     */
    public void markEngaged(double x, double y, double z) {
        this.engX = x;
        this.engY = y;
        this.engZ = z;
        this.engagedTick = now();
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
            smoothHeadValid = false;
            return;
        }

        boolean engaged = now - engagedTick <= LookTunables.ENGAGED_HOLD_TICKS;
        if (engaged) {
            // GUI engagement: square the body up to the target and hold. Unlike idle attention
            // (head leads, body lazily follows), here the BODY yaw itself eases onto the target
            // bearing — a real player at a workbench faces it — while the head eases onto the exact
            // point (the pitch naturally dips to a block centre below the eyes). The pack has already
            // halted locomotion, so the body only turns, never strolls. Everything rides the persisted
            // smoother, the deadzone, and the 50° head/body cap, same as every other regime.
            double edx = engX - body.getX();
            double edy = engY - body.getEyeY();
            double edz = engZ - body.getZ();
            double ehoriz = Math.sqrt(edx * edx + edz * edz);
            float eBody = body.getYRot();
            float rawPitch = (float) (-Math.toDegrees(Math.atan2(edy, ehoriz)));
            // Vertical work point (straight above/below — shaft mining, a ceiling block): yaw is
            // visually irrelevant and its atan2 is numerically arbitrary — hold the current facing
            // and let pitch carry the aim, like a real player digging straight down. Discriminated
            // by PITCH: a block right beside the face is CLOSE horizontally but shallow-pitched,
            // and the body must square onto it (a distance threshold froze exactly that case).
            boolean vertical = Math.abs(rawPitch) >= LookTunables.ENGAGED_VERTICAL_PITCH_DEG
                    || ehoriz < 1.0e-3;
            float engYaw = vertical
                    ? eBody
                    : (float) (Math.toDegrees(Math.atan2(edz, edx)) - 90.0);
            float engPitch = LookMath.clamp(rawPitch,
                    -LookTunables.ENGAGED_MAX_PITCH_DEG, LookTunables.ENGAGED_MAX_PITCH_DEG);

            float eHead = smoothHeadValid ? smoothHead : body.getYHeadRot();
            float ePitch = body.getXRot();

            float newBody = LookMath.approachAngle(eBody, engYaw,
                    LookTunables.ENGAGED_BODY_OMEGA_DEG, LookTunables.BODY_K,
                    LookTunables.DEADZONE_DEG, 0.0f);
            body.setYRot(newBody);

            float newHead = LookMath.approachAngle(eHead, engYaw,
                    LookTunables.ENGAGED_HEAD_OMEGA_DEG, LookTunables.HEAD_K,
                    LookTunables.DEADZONE_DEG, LookTunables.HEAD_MIN_STEP_DEG);
            newHead = LookMath.clampHeadToBody(newHead, newBody, LookTunables.MAX_HEAD_REL_DEG);
            body.setYHeadRot(newHead);
            smoothHead = newHead;
            smoothHeadValid = true;

            float newPitch = LookMath.approachAngle(ePitch, engPitch,
                    LookTunables.PITCH_OMEGA_DEG, LookTunables.PITCH_K,
                    LookTunables.DEADZONE_DEG, 0.0f);
            body.setXRot(newPitch);
            return;
        }

        boolean bodyControlled = now - bodyControlTick <= LookTunables.BODY_CONTROL_HOLD_TICKS;
        boolean intentLive = hasIntent && (now - intentTick) <= LookTunables.INTENT_TTL_TICKS;

        float bodyYaw = body.getYRot();
        // Step from the smoother's persisted pose, NOT the entity field aiStep just clobbered
        // back to yRot — otherwise the glance restarts from "facing travel" every tick and
        // never accumulates (the walking-gaze deadness this field exists to fix).
        float headYaw = smoothHeadValid ? smoothHead : body.getYHeadRot();
        float pitch = body.getXRot();

        if (!intentLive) {
            if (!bodyControlled) {
                // Relax: idle with no intent — ease the head back to the body, level the pitch.
                float relaxedHead = LookMath.approachAngle(headYaw, bodyYaw,
                        LookTunables.HEAD_OMEGA_DEG, LookTunables.HEAD_K,
                        LookTunables.DEADZONE_DEG, 0.0f);
                relaxedHead = LookMath.clampHeadToBody(relaxedHead, bodyYaw, LookTunables.MAX_HEAD_REL_DEG);
                body.setYHeadRot(relaxedHead);
                smoothHead = relaxedHead;
                smoothHeadValid = true;

                float leveled = LookMath.approachAngle(pitch, 0.0f,
                        LookTunables.PITCH_OMEGA_DEG, LookTunables.PITCH_K,
                        LookTunables.DEADZONE_DEG, 0.0f);
                body.setXRot(leveled);
            } else {
                // A mover owns the body and there is nothing to look at: aiStep's
                // head-faces-travel default is correct — resync from it next tick.
                smoothHeadValid = false;
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
            smoothHead = newHead;
            smoothHeadValid = true;
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
            smoothHead = newHead;
            smoothHeadValid = true;
        }

        float newPitch = LookMath.approachAngle(pitch, desiredPitch,
                LookTunables.PITCH_OMEGA_DEG, LookTunables.PITCH_K,
                LookTunables.DEADZONE_DEG, 0.0f);
        body.setXRot(newPitch);
    }
}
