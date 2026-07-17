package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.calc.NavContext;
import com.dwinovo.numen.core.pathing.exec.InputDriver;
import com.dwinovo.numen.core.pathing.exec.MotorGeometry;
import com.dwinovo.numen.core.pathing.exec.PillarDrive;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code escape_to_surface()} — climb a vertical shaft to the open-air surface. The
 * pit accident's direct antidote: with a pickaxe + scaffold it must succeed.
 *
 * <h2>Closed loop</h2>
 * <ul>
 *   <li><b>trigger</b>: the brain (or a "trapped" reflex) orders a surface escape;</li>
 *   <li><b>step</b>: {@link PillarDrive} digs head-room and pillars straight up toward the
 *       heightmap surface of the current column;</li>
 *   <li><b>fluid/unbreakable ceiling</b>: rather than dig into lava/water or grind bedrock, it
 *       SIDESTEPS one block to a neighbour column with a safe ceiling and resumes the climb —
 *       or, if no safe neighbour exists, stops and reports why;</li>
 *   <li><b>terminate</b>: SUCCESS when the feet reach the surface height; FAIL on out-of-scaffold /
 *       boxed-in-by-fluid / bedrock, always naming how far it got;</li>
 *   <li><b>honest settlement</b>: reports start Y → end Y, surface Y, and scaffold consumed.</li>
 * </ul>
 */
public final class EscapeToSurfaceCompanionTask implements CompanionTask {

    private static final double WALK_SPEED = 1.0;
    private static final long TICKS_PER_RUNG = 10 * 20;   // dig + pillar per block, generous
    private static final long BASE_TICKS = 20 * 20;
    private static final long MAX_TASK_TICKS = 6 * 60 * 20;
    private static final int MAX_SIDESTEPS = 8;

    private enum Phase { CLIMB, SIDESTEP }

    private final NumenPlayer player;
    private final EscapeToSurfaceTaskRecord r;

    private Phase phase = Phase.CLIMB;
    private PillarDrive drive;
    private PlayerNav sideNav;
    private BlockPos sideFoot;
    private int targetY;
    private int startY;
    private int startScaffold;
    private int sidesteps = 0;
    private String doneReason = "done";
    private boolean failed = false;

    public EscapeToSurfaceCompanionTask(NumenPlayer player, EscapeToSurfaceTaskRecord record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        startY = player.blockPosition().getY();
        startScaffold = scaffoldCount(player.getInventory());
        recomputeTarget();
        if (atSurface()) {
            doneReason = "already at the surface (y=" + player.blockPosition().getY() + ")";
            r.setState(TaskState.SUCCESS);
            return;
        }
        long climb = Math.max(1, targetY - startY);
        r.extendDeadlineTo(player.level().getGameTime()
                + Math.min(MAX_TASK_TICKS, BASE_TICKS + climb * TICKS_PER_RUNG));
        drive = new PillarDrive(player);
    }

    /** Freeze the surface target for the CURRENT column (recomputed on each sidestep). */
    private void recomputeTarget() {
        BlockPos p = player.blockPosition();
        targetY = player.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
    }

    private boolean atSurface() {
        return player.onGround() && feet().getY() >= targetY;
    }

    @Override
    public TaskState tick() {
        if (atSurface()) {
            doneReason = "reached the surface at y=" + player.blockPosition().getY();
            return TaskState.SUCCESS;
        }
        return switch (phase) {
            case CLIMB -> tickClimb();
            case SIDESTEP -> tickSidestep();
        };
    }

    private TaskState tickClimb() {
        if (drive == null) return TaskState.FAILED;
        switch (drive.tick()) {
            case ROSE, RISING -> {
                return TaskState.RUNNING;
            }
            case NEED_SCAFFOLD -> {
                return fail("out of scaffold blocks after " + progressSummary()
                        + " — carry cobblestone/dirt and call escape_to_surface again");
            }
            case CEILING_FLUID, CEILING_UNBREAKABLE -> {
                return beginSidestep();
            }
        }
        return TaskState.RUNNING;
    }

    /** Find a neighbour column with a safe ceiling to shift into, and start walking there. */
    private TaskState beginSidestep() {
        if (sidesteps >= MAX_SIDESTEPS) {
            return fail("boxed in by lava/water/bedrock overhead after " + progressSummary()
                    + " and " + sidesteps + " sidesteps — no safe way up from here");
        }
        Level level = player.level();
        BlockPos feet = feet();
        for (int[] col : MotorGeometry.sidestepColumns(feet.getX(), feet.getZ())) {
            BlockPos foot = new BlockPos(col[0], feet.getY(), col[1]);
            if (!BlockHelper.isStandable(level, foot)) continue;
            MotorGeometry.Head head = PillarDrive.classifyHead(level, foot.above(2));
            if (head == MotorGeometry.Head.CLEAR || head == MotorGeometry.Head.BREAKABLE) {
                sideFoot = foot;
                sideNav = new PlayerNav(player, foot, WALK_SPEED, this::atSideFoot);
                if (drive != null) { drive.stop(); drive = null; }
                phase = Phase.SIDESTEP;
                sidesteps++;
                return TaskState.RUNNING;
            }
        }
        return fail("blocked by lava/water/bedrock overhead after " + progressSummary()
                + " — no adjacent column has a safe ceiling to climb through");
    }

    private TaskState tickSidestep() {
        if (sideNav == null) return TaskState.FAILED;
        if (atSideFoot()) return resumeClimb();
        return switch (sideNav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> resumeClimb();
            case FAILED -> fail("couldn't sidestep away from the fluid/bedrock ceiling after " + progressSummary()
                    + " (" + sideNav.failReason() + ")");
        };
    }

    private TaskState resumeClimb() {
        if (sideNav != null) { sideNav.stop(); sideNav = null; }
        recomputeTarget();
        drive = new PillarDrive(player);
        phase = Phase.CLIMB;
        return TaskState.RUNNING;
    }

    private boolean atSideFoot() {
        return player.onGround() && feet().equals(sideFoot);
    }

    /** 结算时刻的实时进度(契约方法):起点 y → 当前 y、该柱地表 y。start() 早退时也安全(零/初值)。 */
    @Override
    public String progressSummary() {
        return "rose from y=" + startY + " to y=" + player.blockPosition().getY() + " (surface y=" + targetY + ")";
    }

    private TaskState fail(String reason) {
        doneReason = reason;
        failed = true;
        return TaskState.FAILED;
    }

    private BlockPos feet() {
        return BlockHelper.playerFeet(player.level(), player.getX(), player.getY(), player.getZ());
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
        if (sideNav != null) sideNav.stop();
        InputDriver.halt(player);
        player.setShiftKeyDown(false);
        int endY = player.blockPosition().getY();
        int used = Math.max(0, startScaffold - scaffoldCount(player.getInventory()));
        Map<String, Object> data = new HashMap<>();
        data.put("start_y", startY);
        data.put("end_y", endY);
        data.put("surface_y", targetY);
        data.put("scaffold_used", used);
        data.put("sidesteps", sidesteps);
        String settle = " (y " + startY + "→" + endY + " of surface y=" + targetY + ", "
                + used + " scaffold used, " + sidesteps + " sidestep(s))";
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(doneReason + settle, data);
            case TIMEOUT -> TaskResult.timeout("escape_to_surface timed out — " + progressSummary()
                    + "; call again to resume");
            case CANCELLED -> TaskResult.cancelled("escape_to_surface interrupted — " + progressSummary());
            default -> TaskResult.fail((failed ? doneReason : "escape_to_surface failed") + settle, data);
        };
    }
}
