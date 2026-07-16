package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.calc.NavContext;
import com.dwinovo.numen.core.pathing.exec.InputDriver;
import com.dwinovo.numen.core.pathing.exec.PillarDrive;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.world.entity.player.Inventory;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code pillar_up(height)} — rise N blocks straight up in place, deterministically.
 *
 * <h2>Closed loop</h2>
 * <ul>
 *   <li><b>trigger</b>: the brain orders a lift of {@code height} blocks;</li>
 *   <li><b>step</b>: each rung, {@link PillarDrive} clears any head-room block (native dig),
 *       then sneak-jumps and places a scaffold block underfoot at the apex; a completed rung
 *       ({@link PillarDrive.Result#ROSE}) increments the count;</li>
 *   <li><b>terminate</b>: SUCCESS at {@code height} rungs; FAIL the moment a rung can't be
 *       built, with the precise cause (out of scaffold / bedrock overhead / lava-or-water
 *       overhead) AND how far it got;</li>
 *   <li><b>honest settlement</b>: the result always reports start Y → end Y, blocks risen,
 *       and scaffold consumed — the true terminal state.</li>
 * </ul>
 * Interruptible: a spinal reflex cancelling the task lands in {@link #buildResult} with the
 * body released and the real progress reported.
 */
public final class PillarUpCompanionTask implements CompanionTask {

    private static final long TICKS_PER_RUNG = 8 * 20;   // generous: a rung may need to dig first
    private static final long BASE_TICKS = 5 * 20;
    private static final long MAX_TASK_TICKS = 4 * 60 * 20;

    private final NumenPlayer player;
    private final PillarUpTaskRecord r;

    private PillarDrive drive;
    private int risen = 0;
    private int startY;
    private int startScaffold;
    private String doneReason = "done";
    private boolean failed = false;

    public PillarUpCompanionTask(NumenPlayer player, PillarUpTaskRecord record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        startY = player.blockPosition().getY();
        startScaffold = scaffoldCount(player.getInventory());
        if (player.isInWater() || player.isInLava()) {
            fail("can't pillar up from inside a fluid — swim/step to solid ground first");
            return;
        }
        long extra = Math.min(MAX_TASK_TICKS, BASE_TICKS + (long) r.height * TICKS_PER_RUNG);
        r.extendDeadlineTo(player.level().getGameTime() + extra);
        drive = new PillarDrive(player);
    }

    @Override
    public TaskState tick() {
        if (drive == null) return TaskState.FAILED;
        if (risen >= r.height) {
            doneReason = "rose " + risen + " block(s) to y=" + player.blockPosition().getY();
            return TaskState.SUCCESS;
        }
        switch (drive.tick()) {
            case ROSE -> {
                risen++;
                if (risen >= r.height) {
                    doneReason = "rose " + risen + " block(s) to y=" + player.blockPosition().getY();
                    return TaskState.SUCCESS;
                }
                return TaskState.RUNNING;
            }
            case RISING -> {
                return TaskState.RUNNING;
            }
            case NEED_SCAFFOLD -> {
                return fail("out of scaffold blocks (cobblestone/dirt/…) after " + progress()
                        + " — carry more and call pillar_up again to finish");
            }
            case CEILING_FLUID -> {
                return fail("blocked by lava/water overhead at y=" + (player.blockPosition().getY() + 2)
                        + " after " + progress() + " — clear it or move aside, then retry");
            }
            case CEILING_UNBREAKABLE -> {
                return fail("blocked by an unbreakable block overhead after " + progress()
                        + " — nothing can be dug through here");
            }
        }
        return TaskState.RUNNING;
    }

    /** "rose X/height (y A→B)". */
    private String progress() {
        return "rose " + risen + "/" + r.height + " (y=" + startY + "→" + player.blockPosition().getY() + ")";
    }

    private TaskState fail(String reason) {
        doneReason = reason;
        failed = true;
        return TaskState.FAILED;
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
        if (drive != null) drive.stop();
        InputDriver.halt(player);
        player.setShiftKeyDown(false);
        int endY = player.blockPosition().getY();
        Map<String, Object> data = new HashMap<>();
        data.put("requested", r.height);
        data.put("risen", risen);
        data.put("start_y", startY);
        data.put("end_y", endY);
        data.put("scaffold_used", Math.max(0, startScaffold - scaffoldCount(player.getInventory())));
        String settle = " (y " + startY + "→" + endY + ", " + risen + "/" + r.height + " risen, "
                + data.get("scaffold_used") + " scaffold used)";
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(doneReason + settle, data);
            case TIMEOUT -> TaskResult.timeout("pillar_up timed out — " + progress());
            case CANCELLED -> TaskResult.cancelled("pillar_up interrupted — " + progress());
            default -> TaskResult.fail((failed ? doneReason : "pillar_up failed") + settle, data);
        };
    }
}
