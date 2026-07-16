package com.dwinovo.numen.core.pathing.exec;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Drives a companion {@link ServerPlayer} body the way Carpet's
 * {@code EntityPlayerActionPack} drives a fake player: by setting the player's
 * movement INPUTS ({@code zza}/{@code xxa}, sprint, sneak, jump) and aim, then
 * letting vanilla player physics ({@code LivingEntity.travel}) do the actual
 * stepping, collision and 0.6-block step-up. Replaces the old {@code BodyMotor}
 * which wrote velocity directly onto a Mob's MoveControl.
 *
 * <p>A fake player has no client to send movement packets, so the server's own
 * player tick runs {@code travel} against these inputs and nothing overrides the
 * resulting position — that is what makes input-driving a server-side body work.
 * Inputs are momentary: set them every tick while moving, and {@link #halt} every
 * tick while stopped (otherwise the last forward input keeps it walking).
 */
public final class InputDriver {

    private InputDriver() {}

    /** Face {@code target} (yaw only) and push full forward. Call each tick while travelling. */
    public static void stepToward(ServerPlayer p, Vec3 target, boolean sprint) {
        faceYaw(p, target);
        p.zza = 1.0f;
        p.xxa = 0.0f;
        p.setSprinting(sprint && !p.isShiftKeyDown());
    }

    /**
     * Aim the eyes at a point (yaw + pitch) — e.g. a block being mined/placed, or a combat/reflex
     * target. This is a HARD aim: it snaps the whole aim immediately (unchanged behaviour for every
     * existing tool/combat caller). It additionally latches the companion's look engine to
     * {@code hardAim}, so the smoother yields the head to this snap for a few ticks instead of
     * swivelling it off to an idle attention target (priority ladder: hard-aim outranks all).
     */
    public static void lookAt(ServerPlayer p, Vec3 point) {
        p.lookAt(EntityAnchorArgument.Anchor.EYES, point);
        if (p instanceof NumenPlayer np) {
            np.getLook().markHardAim();
        }
    }

    /**
     * Register a SMOOTH gaze intent — the attention brain's "please look here" for idle/walking.
     * Unlike {@link #lookAt}, this does not move the body at all this tick; the look engine eases the
     * head (and, when idle, the body) toward {@code point} over the coming ticks, honouring the 50°
     * head/body cap and the quantization deadzone. No-op for a non-companion body.
     */
    public static void setLookIntent(ServerPlayer p, Vec3 point) {
        if (p instanceof NumenPlayer np) {
            np.getLook().lookAt(point.x, point.y, point.z);
        }
    }

    /** Drop any smooth gaze intent — the head relaxes back toward the body over the coming ticks. */
    public static void clearLookIntent(ServerPlayer p) {
        if (p instanceof NumenPlayer np) {
            np.getLook().clearIntent();
        }
    }

    /**
     * Upward impulse, routed the way vanilla routes pressing the jump key: a ground hop
     * on land ({@code jumpFromGround}), or a swim-up stroke in water/lava (vanilla
     * {@code jumpInLiquid} = +0.04/tick). This is why Baritone stays at the water surface
     * (it presses Input.JUMP when its feet sink below the lane) — a fake player has no
     * client to translate a key into the liquid case, so we do it here. Call every tick
     * you want to keep rising; in water it's the per-tick stroke, not a one-shot.
     */
    public static void jump(ServerPlayer p) {
        if (p.onGround()) {
            p.jumpFromGround();
        } else if (p.isInWater() || p.isInLava()) {
            p.setDeltaMovement(p.getDeltaMovement().add(0.0, 0.04, 0.0));
        }
    }

    public static void sneak(ServerPlayer p, boolean on) {
        p.setShiftKeyDown(on);
    }

    /** Zero all locomotion input. Call each tick while idle/arrived. */
    public static void halt(ServerPlayer p) {
        p.zza = 0.0f;
        p.xxa = 0.0f;
        p.setSprinting(false);
    }

    /**
     * Turn the body to face {@code target} horizontally — travel goes where {@code yRot} points, so
     * locomotion (pathing / flee) OWNS the body yaw and it is set every tick. We latch the look
     * engine to {@code bodyControlled}: while a mover owns {@code yRot}, the smoother must not fight
     * it for the body — it drives ONLY the head (borrowing ±50° of this travel yaw) toward whatever
     * the attention brain wants, so the companion can glance around while it walks. {@code yHeadRot}
     * is still set here to the travel yaw as the safe default; the look engine overrides it later the
     * same tick when it has a gaze intent. (26.1.2 study §B4: path active → yRot is the path's.)
     */
    private static void faceYaw(ServerPlayer p, Vec3 target) {
        double dx = target.x - p.getX();
        double dz = target.z - p.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        p.setYRot(yaw);
        p.setYHeadRot(yaw);
        if (p instanceof NumenPlayer np) {
            np.getLook().markBodyControlled();
        }
    }
}
