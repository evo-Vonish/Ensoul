package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.exec.InputDriver;
import com.dwinovo.numen.core.pathing.exec.TurtleDrive;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code turtle_up()} — burrow down a couple of blocks, cap the hole overhead, and hold until safe. The
 * brain-orderable face of the {@link TurtleDrive} muscle the reflex layer also fires automatically for a
 * CORNERED companion.
 *
 * <h2>Closed loop</h2>
 * <ul>
 *   <li><b>dig + seal</b> — {@link TurtleDrive} digs the shaft and caps it (or crouches if the pack has no block);</li>
 *   <li><b>hold</b> — once sealed, re-check every {@value #REASSESS_TICKS} ticks whether the coast is clear
 *       (no hostiles within {@value #SAFE_RADIUS} blocks AND it's daytime, or the owner is right here);</li>
 *   <li><b>emerge</b> — when clear, break the cap and pillar back up, then SUCCESS; TIMEOUT / owner-Stop end it
 *       where it stands (the brain can call {@code escape_to_surface} to leave a still-sealed pit).</li>
 * </ul>
 */
public final class TurtleUpCompanionTask implements CompanionTask {

    private static final long BASE_TICKS = 30 * 20;
    private static final long MAX_TASK_TICKS = 5 * 60 * 20;
    private static final int REASSESS_TICKS = 20;
    private static final double SAFE_RADIUS = 12.0;
    private static final double OWNER_NEAR = 8.0;

    private final NumenPlayer player;
    private final TurtleUpTaskRecord r;

    private TurtleDrive drive;
    private long nextReassess;
    private String doneReason = "turtle_up done";

    public TurtleUpCompanionTask(NumenPlayer player, TurtleUpTaskRecord record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        drive = new TurtleDrive(player);
        nextReassess = player.level().getGameTime() + BASE_TICKS;   // hold at least this long before first re-check
        r.extendDeadlineTo(player.level().getGameTime() + MAX_TASK_TICKS);
    }

    @Override
    public TaskState tick() {
        if (drive == null) return TaskState.FAILED;
        long now = player.level().getGameTime();
        return switch (drive.tick()) {
            case WORKING, EMERGING -> TaskState.RUNNING;
            case SEALED -> {
                if (now >= nextReassess) {
                    nextReassess = now + REASSESS_TICKS;
                    if (coastClear()) {
                        doneReason = "威胁散去,已破土而出";
                        drive.breakOut();
                    }
                }
                yield TaskState.RUNNING;
            }
            case DONE -> TaskState.SUCCESS;
        };
    }

    /** Coast is clear when no hostile is within {@link #SAFE_RADIUS} and either it's daytime or the owner is here. */
    private boolean coastClear() {
        AABB box = player.getBoundingBox().inflate(SAFE_RADIUS);
        for (Monster m : player.level().getEntitiesOfClass(Monster.class, box)) {
            if (m.isAlive()) return false;
        }
        if (player.level().isBrightOutside()) return true;
        ServerPlayer owner = player.resolveOwnerPlayer();
        return owner != null && owner.level() == player.level() && player.distanceTo(owner) <= OWNER_NEAR;
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        int depth = drive == null ? 0 : drive.depthReached();
        boolean capped = drive != null && drive.capped();
        if (drive != null) drive.stop();
        InputDriver.halt(player);
        player.setShiftKeyDown(false);
        Map<String, Object> data = new HashMap<>();
        data.put("depth", depth);
        data.put("capped", capped);
        String settle = "(下挖 " + depth + " 格," + (capped ? "已封顶" : "仅蹲坑") + ")";
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(doneReason + settle, data);
            case TIMEOUT -> TaskResult.timeout("turtle_up 超时,仍在掩体中 " + settle + ";可调用 escape_to_surface 出坑");
            case CANCELLED -> TaskResult.cancelled("turtle_up 被打断 " + settle);
            default -> TaskResult.fail("turtle_up 失败 " + settle, data);
        };
    }
}
