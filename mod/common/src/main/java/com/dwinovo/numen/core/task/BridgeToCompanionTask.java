package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.calc.NavContext;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code bridge_to(x,y,z)} — reach one exact cell by bridging across gaps / up gentle
 * slopes, laying scaffold as it goes.
 *
 * <h2>Closed loop</h2>
 * <ul>
 *   <li><b>trigger</b>: the brain names an exact target cell to bridge to;</li>
 *   <li><b>step</b>: it drives {@link PlayerNav} at an exact goal cell, whose
 *       executor lays a plank of scaffold under each floorless step with the same edge-sneak
 *       BRIDGE maneuver the pathfinder already proves — deterministic (A* has no randomness);</li>
 *   <li><b>terminate</b>: SUCCESS on reaching the exact cell (or, when the terrain won't allow the
 *       exact cell, a teaching success within {@value #NEAR_SUCCESS_RADIUS} blocks); FAIL when the
 *       planner is walled off / out of scaffold, with the concrete reason;</li>
 *   <li><b>honest settlement</b>: the result always reports the ACTUAL cell reached and scaffold
 *       consumed.</li>
 * </ul>
 */
public final class BridgeToCompanionTask implements CompanionTask {

    private static final double WALK_SPEED = 1.0;
    private static final long TICKS_PER_BLOCK = 20;
    private static final long BASE_TICKS = 30 * 20;
    private static final long MAX_TASK_TICKS = 5 * 60 * 20;
    /** When the planner can't reach the exact cell, a stop within this counts as a teaching success. */
    private static final double NEAR_SUCCESS_RADIUS = 2.0;

    private final NumenPlayer player;
    private final BridgeToTaskRecord r;
    private final BlockPos target;

    private PlayerNav nav;
    private int startScaffold;

    public BridgeToCompanionTask(NumenPlayer player, BridgeToTaskRecord record) {
        this.player = player;
        this.r = record;
        this.target = record.target;
    }

    @Override
    public void start() {
        startScaffold = scaffoldCount(player.getInventory());
        if (reached()) return;   // already there; tick() reports SUCCESS immediately
        long extra = Math.min(MAX_TASK_TICKS, BASE_TICKS + (long) (dist() * TICKS_PER_BLOCK));
        r.extendDeadlineTo(player.level().getGameTime() + extra);
        nav = new PlayerNav(player, target, WALK_SPEED, this::reached);
        nav.setHighlights(() -> java.util.List.of(target));
    }

    @Override
    public TaskState tick() {
        if (reached()) return TaskState.SUCCESS;
        if (nav == null) return TaskState.FAILED;
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> TaskState.SUCCESS;
            case FAILED -> closeEnough() ? TaskState.SUCCESS : TaskState.FAILED;
        };
    }

    private boolean reached() {
        return feet().equals(target);
    }

    private boolean closeEnough() {
        return player.distanceToSqr(target.getX() + 0.5, target.getY(), target.getZ() + 0.5)
                <= NEAR_SUCCESS_RADIUS * NEAR_SUCCESS_RADIUS;
    }

    private BlockPos feet() {
        return BlockHelper.playerFeet(player.level(), player.getX(), player.getY(), player.getZ());
    }

    private double dist() {
        return Math.sqrt(player.distanceToSqr(target.getX() + 0.5, target.getY(), target.getZ() + 0.5));
    }

    private static int scaffoldCount(Inventory inv) {
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (NavContext.isScaffold(inv.getItem(i))) n += inv.getItem(i).getCount();
        }
        return n;
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        String failReason = nav != null ? nav.failReason() : "no path";
        if (nav != null) nav.stop();
        BlockPos feet = feet();
        int used = Math.max(0, startScaffold - scaffoldCount(player.getInventory()));
        Map<String, Object> data = new HashMap<>();
        data.put("final_x", player.getX());
        data.put("final_y", player.getY());
        data.put("final_z", player.getZ());
        data.put("scaffold_used", used);
        String tgt = target.getX() + "," + target.getY() + "," + target.getZ();
        String at = feet.getX() + "," + feet.getY() + "," + feet.getZ();
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(reached()
                    ? "bridged to " + tgt + " (" + used + " scaffold used)"
                    : "bridged to within reach of " + tgt + ", standing at " + at + " (" + used + " scaffold used)", data);
            case TIMEOUT -> TaskResult.timeout("bridge_to timed out at " + at + " heading for " + tgt
                    + "; call again to resume");
            case CANCELLED -> TaskResult.cancelled("bridge_to interrupted at " + at);
            default -> TaskResult.fail("couldn't bridge to " + tgt + " — stopped at " + at + ". " + failReason
                    + " (" + used + " scaffold used)", data);
        };
    }
}
