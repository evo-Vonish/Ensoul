package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.exec.InputDriver;
import com.dwinovo.numen.core.pathing.exec.Interaction;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.task.CompanionTask;
import com.dwinovo.numen.core.task.PlayerInv;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.core.task.TaskState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code interact_at} on the player body — the point-aimed native interaction (BLOCK + AIR).
 * Walk within reach of the aim (if one is given), look at it, fire ONE native crosshair
 * raytrace ({@link Interaction#nativeRaytrace}) and press the requested mouse button on
 * whatever it resolves to ({@link Interaction#forHit}): break / activate the block hit, or —
 * on a clear-air aim — use the held item in that direction (throw / eat / draw). The mouse
 * model is the two record fields {@code button} (left/right) × {@code holdTicks} (tap/hold).
 */
public final class InteractAtCompanionTask implements CompanionTask {

    private static final double REACH = 4.5;
    private static final double REACH_SQR = REACH * REACH;
    private static final double WALK_SPEED = 1.0;

    // ---- pre-click settle beat (right-click on a real aim only) ----
    /** Turn toward the target for at least this many ticks before the click (never snap-and-open). */
    private static final int SETTLE_MIN_TICKS = 2;
    /** ...and never wait longer than this, so a partly-blocked bearing can't stall the interaction. */
    private static final int SETTLE_MAX_TICKS = 4;
    /** Fire early once the head is within this of the target bearing (deg) — "roughly facing it". */
    private static final float SETTLE_ALIGN_DEG = 10.0f;

    private final NumenPlayer player;
    private final InteractAtTaskRecord r;

    private PlayerNav nav;
    private Interaction interaction;
    private boolean prepared;          // one-time on-arrival prep (hold item) has run
    private int settleTicks = -1;      // ticks spent easing into the engaged posture before the click
    private long holdUntil = -1;       // game tick to release a fixed-duration hold (holdTicks > 0)
    private String doneReason = "done";
    // A right-click that activated a real block (a station's GUI): captured so the
    // result can report it and the agent loop can remember it in <known_blocks>.
    private net.minecraft.core.BlockPos activatedBlock;
    private String activatedBlockId;

    public InteractAtCompanionTask(NumenPlayer player, InteractAtTaskRecord record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        // If an item to use was named, fail fast unless we actually carry it.
        if (r.item != null && PlayerInv.count(player.getInventory(), r.item) <= 0) {
            doneReason = "don't have " + BuiltInRegistries.ITEM.getKey(r.item).getPath() + " to use";
            r.setState(TaskState.FAILED);
            return;
        }
        // An aim point → walk within arm's reach of it first. No aim → act from where we stand,
        // facing forward (in-air use, e.g. throwing straight ahead).
        if (r.aim != null) {
            nav = new PlayerNav(player, r.aim, WALK_SPEED, this::withinReach);
        }
    }

    @Override
    public TaskState tick() {
        // 1) Travel to within reach of the aim.
        if (r.aim != null && !withinReach()) {
            if (nav == null) return TaskState.FAILED;
            return switch (nav.tick()) {
                case RUNNING, ARRIVED -> TaskState.RUNNING;
                case FAILED -> {
                    doneReason = "can't reach " + aimLabel() + ": " + nav.failReason();
                    yield TaskState.FAILED;
                }
            };
        }

        // 2) Resolve the crosshair once we're in position, then drive the action.
        if (interaction == null) {
            // One-time on-arrival prep: switch the named item into hand (must NOT repeat during the
            // settle beat below — holdInHand SWAPS a main-inventory slot, so re-running it thrashes).
            if (!prepared) {
                prepared = true;
                if (r.item != null) {
                    player.holdInHand(PlayerInv.findSlot(player.getInventory(), r.item));
                }
            }
            // Settle beat: a right-click on a real block/entity aim is a GUI candidate — don't snap
            // and click blind. Ease into an engaged posture first (halt + feed the look engine the
            // gaze so head and body turn to face the target), then click once roughly aligned or a
            // short cap elapses. Opening a menu then reads as "walked up, turned to it, used it".
            // Left-clicks (break/attack) keep their snappy hard-aim; in-air uses have nothing to face.
            if (r.aim != null && button() == Interaction.Button.USE && !settleReady()) {
                return TaskState.RUNNING;
            }
            if (r.aim != null) {
                InputDriver.lookAt(player, Vec3.atCenterOf(r.aim));
            }
            HitResult hit = Interaction.nativeRaytrace(player, REACH);
            // A consumable / ender pearl used in the AIR is body-bound (would feed or teleport the
            // fake player) — refuse even when it's just whatever happened to be in hand.
            if (button() == Interaction.Button.USE && hit.getType() == HitResult.Type.MISS) {
                String reason = InteractAtTaskRecord.bodyBoundReason(player.getMainHandItem().getItem());
                if (reason != null) {
                    doneReason = reason;
                    return TaskState.FAILED;
                }
            }
            // A right-click landing on a block activates it (opens a station's GUI,
            // flips a switch, …). Remember the block we touched so <known_blocks> can
            // walk us back to stations we've used, not just ones we placed. The harvest
            // filters to tracked station types; doors/buttons fall away there.
            if (button() == Interaction.Button.USE && hit instanceof net.minecraft.world.phys.BlockHitResult bhr) {
                activatedBlock = bhr.getBlockPos();
                activatedBlockId = BuiltInRegistries.BLOCK
                        .getKey(player.level().getBlockState(activatedBlock).getBlock()).getPath();
            }
            interaction = Interaction.forHit(player, hit, button(), r.holdTicks);
            if (interaction == null) {       // left-click on air — a swing, nothing to do
                doneReason = "nothing under the aim (left-click in the air)";
                return TaskState.SUCCESS;
            }
            if (r.holdTicks > 0) {
                holdUntil = player.level().getGameTime() + r.holdTicks;
            }
        }

        // 3) A fixed-duration hold ends when its window elapses (Carpet: release the button).
        if (holdUntil >= 0 && player.level().getGameTime() >= holdUntil) {
            interaction.stop();
            doneReason = describeDone();
            return TaskState.SUCCESS;
        }
        return switch (interaction.tick()) {
            case DONE -> {
                doneReason = describeDone();
                yield TaskState.SUCCESS;
            }
            case FAILED -> {
                doneReason = interaction.failReason();
                yield TaskState.FAILED;
            }
            case RUNNING -> TaskState.RUNNING;
        };
    }

    private Interaction.Button button() {
        return r.button == InteractAtTaskRecord.Button.LEFT
                ? Interaction.Button.ATTACK : Interaction.Button.USE;
    }

    /**
     * One tick of the pre-click settle beat, run every tick until it returns true: halt, feed the
     * look engine the engaged gaze so head+body smoothly turn onto the target, and report whether
     * we've eased enough to fire — roughly aligned (head within {@link #SETTLE_ALIGN_DEG} of the
     * target bearing) after a minimum, or the {@link #SETTLE_MAX_TICKS} cap. The subsequent
     * hard-aim in the caller still guarantees the raytrace hits; this only makes the approach look
     * natural, and bounds its own duration so it never breaks the task's deadline semantics.
     */
    private boolean settleReady() {
        settleTicks++;
        Vec3 aim = Vec3.atCenterOf(r.aim);
        InputDriver.halt(player);
        player.getLook().markEngaged(aim.x, aim.y, aim.z);
        if (settleTicks >= SETTLE_MAX_TICKS) {
            return true;
        }
        if (settleTicks < SETTLE_MIN_TICKS) {
            return false;
        }
        double dx = aim.x - player.getX();
        double dz = aim.z - player.getZ();
        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        return Math.abs(Mth.wrapDegrees(targetYaw - player.getYHeadRot())) < SETTLE_ALIGN_DEG;
    }

    private boolean withinReach() {
        return player.onGround()
                && player.distanceToSqr(Vec3.atCenterOf(r.aim)) <= REACH_SQR;
    }

    private String aimLabel() {
        return r.aim.getX() + "," + r.aim.getY() + "," + r.aim.getZ();
    }

    private String describeDone() {
        String verb = r.button == InteractAtTaskRecord.Button.LEFT ? "left-clicked" : "right-clicked";
        return verb + (r.aim != null ? " " + aimLabel() : " (forward)");
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        if (nav != null) nav.stop();
        if (interaction != null) interaction.stop();
        Map<String, Object> data = new HashMap<>();
        data.put("button", r.button == InteractAtTaskRecord.Button.LEFT ? "left" : "right");
        if (r.aim != null) {
            data.put("x", r.aim.getX());
            data.put("y", r.aim.getY());
            data.put("z", r.aim.getZ());
        }
        // Report the activated station (and its exact position, authoritative over the
        // raw aim) so the agent loop can harvest it into <known_blocks>.
        if (activatedBlock != null) {
            data.put("block", activatedBlockId);
            data.put("x", activatedBlock.getX());
            data.put("y", activatedBlock.getY());
            data.put("z", activatedBlock.getZ());
        }
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(doneReason, data);
            case TIMEOUT -> TaskResult.timeout("timed out before interacting at " + (r.aim != null ? aimLabel() : "forward"));
            case CANCELLED -> TaskResult.cancelled("interact_at interrupted");
            default -> TaskResult.fail(doneReason, data);
        };
    }
}
