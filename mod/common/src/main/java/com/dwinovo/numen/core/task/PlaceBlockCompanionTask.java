package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.exec.MotorGeometry;
import com.dwinovo.numen.core.pathing.exec.PlaceManeuver;
import com.dwinovo.numen.core.pathing.exec.Placement;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code place_block} on the player body: walk within reach (full pathfinding —
 * digs / bridges / climbs to get there), then place like a real player with the
 * shared {@link PlaceManeuver} "edge sneak" in its SETTLE mode — sneak once, hold
 * still at a chosen stance, look at the support face, place natively.
 *
 * <h2>Placement fault tolerance (the井底 lesson)</h2>
 * A single fixed stance often can't place: the body may be standing IN the target
 * cell (you can't fill a cell you occupy) or boxed with no line of sight to a
 * support face — the failures that burned 110 rounds at the bottom of a hole and
 * fired live stop-loss notices. So the placement is a small state machine:
 * <ol>
 *   <li><b>APPROACH</b> — pathfind adjacent to the target;</li>
 *   <li><b>PLACE</b> — run ONE settle maneuver from the current stance; while it
 *       runs we never hand control back to the pathfinder (that flip was the
 *       "crouch/stand, forward/back" oscillation);</li>
 *   <li><b>REPOSITION</b> — if the maneuver can't place, pick the next standable
 *       neighbour cell with a chance at a support face ({@link MotorGeometry#stanceCandidates}),
 *       walk there (stepping OUT of the target cell when we were standing in it) and
 *       retry. Candidates exhausted → fail honestly, naming every stance tried.</li>
 * </ol>
 */
public final class PlaceBlockCompanionTask implements CompanionTask {

    private static final double REACH_SQR = 4.5 * 4.5;
    private static final double WALK_SPEED = 1.0;
    /** How many distinct stances to try before giving up (bounds the nav trips). */
    private static final int MAX_STANCES = 6;
    /** Per-stance travel budget extension so a multi-stance rescue isn't guillotined by
     *  the base 30s deadline mid-walk. */
    private static final long STANCE_TRAVEL_TICKS = 15 * 20;

    private enum Phase { APPROACH, PLACE, REPOSITION, DONE }

    private final NumenPlayer player;
    private final PlaceBlockTaskRecord r;

    private Phase phase = Phase.APPROACH;
    private PlayerNav nav;
    private PlaceManeuver maneuver;
    /** Current stance we're walking to / placing from; null = the initial approach to the target. */
    private BlockPos stance;
    /** Stances already attempted (for honest reporting + to skip on the next reposition). */
    private final Set<BlockPos> tried = new LinkedHashSet<>();
    private String doneReason = "done";

    public PlaceBlockCompanionTask(NumenPlayer player, PlaceBlockTaskRecord record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        Level level = player.level();
        if (PlayerInv.count(player.getInventory(), r.item) <= 0) {
            fail("no " + r.label + " in inventory to place");
            return;
        }
        // Occupancy is the one thing worth a fast, clear message; everything else (support faces,
        // modded placement rules) is left to vanilla's own placement to accept or reject — we don't
        // second-guess it with our own heuristic. Vanilla's `canBeReplaced` is the same test it uses.
        BlockState existing = level.getBlockState(r.pos);
        if (!existing.isAir() && !existing.canBeReplaced()) {
            fail("target " + coords() + " is already occupied by "
                    + BuiltInRegistries.BLOCK.getKey(existing.getBlock()).getPath());
            return;
        }
        // First approach: walk adjacent to the target itself (the common case places from wherever
        // the planner stops); only if that stance can't place do we start hunting explicit stances.
        nav = new PlayerNav(player, r.pos, WALK_SPEED, this::withinReach);
    }

    @Override
    public TaskState tick() {
        if (player.level().getBlockState(r.pos).is(r.block)) {
            doneReason = "placed " + r.label + " at " + coords() + orientation();
            return TaskState.SUCCESS;
        }
        return switch (phase) {
            case APPROACH -> tickApproach();
            case PLACE -> tickPlace();
            case REPOSITION -> tickReposition();
            case DONE -> TaskState.FAILED;   // unreachable; buildResult already set
        };
    }

    // ---- APPROACH: walk to the current goal (target on the first pass, a stance thereafter) ----

    private TaskState tickApproach() {
        if (arrived()) {
            beginPlace();
            return TaskState.RUNNING;
        }
        if (nav == null) return TaskState.FAILED;
        return switch (nav.tick()) {
            case ARRIVED -> {
                // Arrived where the planner could get us. If that's a place-able stance, place;
                // otherwise fall through to hunting explicit stances.
                beginPlace();
                yield TaskState.RUNNING;
            }
            case RUNNING -> TaskState.RUNNING;
            case FAILED -> enterReposition("can't reach a spot to place (" + nav.failReason() + ")");
        };
    }

    /** Arrival for the current leg: the first approach places from wherever it can reach the
     *  target; a reposition leg must actually stand on the chosen stance first (so it doesn't
     *  re-attempt from the spot that just failed). */
    private boolean arrived() {
        return (stance == null) ? readyToPlace() : atStance();
    }

    /** True when the body can attempt a placement from where it stands right now. */
    private boolean readyToPlace() {
        if (!withinReach()) return false;
        // Never try to place while standing in the target cell (feet or head occupies it) — vanilla
        // refuses, and the maneuver would just burn its window. Reposition instead (step out).
        BlockPos feet = feet();
        return !(feet.equals(r.pos) || feet.above().equals(r.pos));
    }

    private void beginPlace() {
        if (nav != null) { nav.stop(); nav = null; }   // done walking; stop before we place (or reposition)
        if (stopBecauseBodyInCell()) return;           // may spin up a fresh reposition nav
        maneuver = new PlaceManeuver(player, r.pos,
                () -> PlayerInv.findSlot(player.getInventory(), r.item),
                () -> player.level().getBlockState(r.pos).is(r.block),
                new PlaceManeuver.Hints(r.facing, r.axis, r.topHalf), r.block, /* settle = */ true);
        tried.add(currentStance());
        phase = Phase.PLACE;
    }

    /** If the body is standing in the target cell, skip straight to repositioning (step out). */
    private boolean stopBecauseBodyInCell() {
        BlockPos feet = feet();
        if (feet.equals(r.pos) || feet.above().equals(r.pos)) {
            enterReposition("standing in the target cell " + coords());
            return true;
        }
        return false;
    }

    // ---- PLACE: run one settle maneuver; do NOT hand back to nav mid-maneuver ----

    private TaskState tickPlace() {
        return switch (maneuver.tick()) {
            case DONE -> {
                doneReason = "placed " + r.label + " at " + coords() + orientation();
                yield TaskState.SUCCESS;
            }
            case FAILED -> enterReposition(maneuver.failReason());
            case RUNNING -> TaskState.RUNNING;
        };
    }

    // ---- REPOSITION: pick the next standable stance with a shot at a support face ----

    /** Record why the current stance failed, release the body, and move to the reposition phase. */
    private TaskState enterReposition(String lastReason) {
        this.lastFailReason = lastReason;
        if (maneuver != null) { maneuver.stop(); maneuver = null; }
        if (nav != null) { nav.stop(); nav = null; }
        phase = Phase.REPOSITION;
        return tickReposition();
    }

    private String lastFailReason = "couldn't place";

    private TaskState tickReposition() {
        Level level = player.level();
        // A target with no support face at all can never be placed from any stance — say so once.
        if (!Placement.hasAnySupport(level, r.pos)) {
            doneReason = "can't place at " + coords() + " — nothing solid beside or below it to place "
                    + "against (it's over air or a non-solid block). Pick a cell that touches solid ground.";
            return TaskState.FAILED;
        }
        if (tried.size() >= MAX_STANCES) {
            return giveUp();
        }
        BlockPos next = nextStance(level);
        if (next == null) {
            return giveUp();
        }
        stance = next;
        tried.add(next);   // mark now, so an unreachable stance can't be re-picked into a loop
        r.extendDeadlineTo(player.level().getGameTime() + STANCE_TRAVEL_TICKS);
        nav = new PlayerNav(player, stance, WALK_SPEED, this::atStance);
        phase = Phase.APPROACH;
        return TaskState.RUNNING;
    }

    /** Nearest untried standable neighbour stance, or null when none remain. */
    private BlockPos nextStance(Level level) {
        MotorGeometry.Cell standable = (x, y, z) -> BlockHelper.isStandable(level, new BlockPos(x, y, z));
        BlockPos feet = feet();
        List<MotorGeometry.Foot> cands = MotorGeometry.stanceCandidates(
                r.pos.getX(), r.pos.getY(), r.pos.getZ(), standable,
                feet.getX(), feet.getY(), feet.getZ());
        for (MotorGeometry.Foot f : cands) {
            BlockPos c = new BlockPos(f.x(), f.y(), f.z());
            if (!tried.contains(c)) return c;
        }
        return null;
    }

    private TaskState giveUp() {
        doneReason = "couldn't place " + r.label + " at " + coords() + " — " + lastFailReason
                + ". Tried " + tried.size() + " stance(s): " + describeTried()
                + ". No standable neighbour has a clear line to a support face; clear the way "
                + "or target a cell with an exposed solid face.";
        return TaskState.FAILED;
    }

    private String describeTried() {
        List<String> parts = new ArrayList<>();
        for (BlockPos p : tried) {
            parts.add(p.getX() + "," + p.getY() + "," + p.getZ());
        }
        return String.join(" / ", parts);
    }

    /** The stance the body is presently placing from (an explicit stance, else its feet cell). */
    private BlockPos currentStance() {
        return stance != null ? stance : feet();
    }

    private boolean atStance() {
        return player.onGround() && feet().equals(stance);
    }

    private boolean withinReach() {
        return player.onGround() && player.distanceToSqr(Vec3.atCenterOf(r.pos)) <= REACH_SQR;
    }

    /** Slab-aware feet cell (Baritone playerFeet) — the pathing node, not raw blockPosition. */
    private BlockPos feet() {
        return BlockHelper.playerFeet(player.level(), player.getX(), player.getY(), player.getZ());
    }

    private String coords() {
        return r.pos.getX() + "," + r.pos.getY() + "," + r.pos.getZ();
    }

    /** Report the ACTUAL orientation the block landed in (so the model can see + correct it), flagging
     *  any property that didn't come out the way it asked. Empty for blocks with no orientation. */
    private String orientation() {
        BlockState s = player.level().getBlockState(r.pos);
        List<String> parts = new ArrayList<>();
        Direction f = s.hasProperty(BlockStateProperties.FACING) ? s.getValue(BlockStateProperties.FACING)
                : s.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? s.getValue(BlockStateProperties.HORIZONTAL_FACING)
                : null;
        if (f != null) parts.add("facing " + f.getName() + mismatch(r.facing != null && r.facing != f, r.facing));
        Direction.Axis ax = s.hasProperty(BlockStateProperties.AXIS) ? s.getValue(BlockStateProperties.AXIS)
                : s.hasProperty(BlockStateProperties.HORIZONTAL_AXIS) ? s.getValue(BlockStateProperties.HORIZONTAL_AXIS)
                : null;
        if (ax != null) parts.add("axis " + ax.getName() + mismatch(r.axis != null && r.axis != ax, r.axis));
        Boolean top = topHalf(s);
        if (top != null) {
            parts.add((top ? "top" : "bottom") + " half"
                    + mismatch(r.topHalf != null && !r.topHalf.equals(top), r.topHalf == null ? null : (r.topHalf ? "top" : "bottom")));
        }
        return parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
    }

    private static Boolean topHalf(BlockState s) {
        if (s.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            SlabType t = s.getValue(BlockStateProperties.SLAB_TYPE);
            return t == SlabType.DOUBLE ? null : t == SlabType.TOP;
        }
        if (s.hasProperty(BlockStateProperties.HALF)) {
            return s.getValue(BlockStateProperties.HALF) == Half.TOP;
        }
        return null;
    }

    private static String mismatch(boolean differs, Object wanted) {
        return differs ? " [wanted " + wanted + "]" : "";
    }

    private void fail(String reason) {
        doneReason = reason;
        r.setState(TaskState.FAILED);
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        if (nav != null) nav.stop();
        if (maneuver != null) maneuver.stop();
        Map<String, Object> data = new HashMap<>();
        data.put("block", r.label);
        data.put("x", r.pos.getX());
        data.put("y", r.pos.getY());
        data.put("z", r.pos.getZ());
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(doneReason, data);
            case TIMEOUT -> TaskResult.timeout("timed out before placing " + r.label + " at " + coords()
                    + (tried.isEmpty() ? "" : " (tried stances: " + describeTried() + ")"));
            case CANCELLED -> TaskResult.cancelled("place_block interrupted");
            default -> TaskResult.fail(doneReason, data);
        };
    }
}
