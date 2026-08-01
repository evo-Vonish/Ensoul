package com.dwinovo.numen.core.pathing.movement;

import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One edge in the pathfinding graph: a single step from a feet-position to an
 * adjacent feet-position, possibly requiring some blocks to be broken and/or
 * one scaffolding block to be placed. Mirrors a Baritone {@code Movement} /
 * mineflayer {@code Move} node.
 *
 * <p>Immutable value object produced by {@link Moves} during A* expansion and
 * consumed by the {@code PlayerPathExecutor} at runtime. The {@link #cost} already
 * folds in walk-time + mining-time + placement-penalty (or
 * {@link com.dwinovo.numen.core.pathing.util.ActionCosts#COST_INF} if infeasible —
 * such movements are never emitted, {@link Moves} returns {@code null}).
 */
public final class Movement {

    /** Kind tag, used by the executor to pick an animation / phase order.
     *  {@code FLY} is the creative-flight edge (creative-motion design v1): emitted
     *  ONLY by {@code FlyPlanner}'s independent 3D air search and consumed by
     *  {@code FlyPathExecutor} — the ground A* ({@code Moves}) never generates it,
     *  and {@code PlayerPathExecutor} never receives it. */
    public enum Kind { TRAVERSE, ASCEND, DESCEND, FALL, DIAGONAL, PILLAR, DIG_DOWN, PARKOUR, FLY }

    public final Kind kind;
    /** Feet position the entity starts this step at. */
    public final BlockPos src;
    /** Feet position the entity ends this step at. */
    public final BlockPos dest;
    /** Total step cost in ticks. */
    public final double cost;
    /** Blocks to break before/while moving (head & feet obstructions). May be empty. */
    public final List<BlockPos> toBreak;
    /** Scaffolding block to place (floor under {@link #dest}), or {@code null}. */
    public final BlockPos toPlace;

    /** Lazily-built set of feet cells the entity may legitimately occupy mid-move. */
    private Set<BlockPos> validPositions;

    public Movement(Kind kind, BlockPos src, BlockPos dest, double cost,
                    List<BlockPos> toBreak, BlockPos toPlace) {
        this.kind = kind;
        this.src = src.immutable();
        this.dest = dest.immutable();
        this.cost = cost;
        this.toBreak = List.copyOf(toBreak);
        this.toPlace = toPlace == null ? null : toPlace.immutable();
    }

    /**
     * The feet positions the entity may legitimately stand in <em>during</em>
     * this movement — the executor's re-localization anchor. If the entity is
     * pushed/falls/overshoots, the executor matches its real feet against the
     * valid sets of nearby movements to resync the path index <em>without</em>
     * throwing the whole path away (Baritone {@code Movement.getValidPositions}).
     *
     * <p>Derived purely from {@link #src}/{@link #dest}/{@link #kind}:
     * <ul>
     *   <li>most moves — {@code {src, dest}};</li>
     *   <li>ASCEND — adds {@code src.above()} (the jump-apex feet cell);</li>
     *   <li>DIAGONAL — adds the two orthogonal corner cells we cut between;</li>
     *   <li>DESCEND/FALL — adds the horizontal entry cell plus the whole column
     *       fallen through, since Y diverges legitimately during the drop.</li>
     * </ul>
     */
    public Set<BlockPos> validPositions() {
        Set<BlockPos> set = validPositions;
        if (set != null) return set;
        set = new HashSet<>(6);
        set.add(src);
        set.add(dest);
        switch (kind) {
            case ASCEND -> set.add(src.above());
            case DIAGONAL -> {
                int dx = dest.getX() - src.getX();
                int dz = dest.getZ() - src.getZ();
                set.add(src.offset(dx, 0, 0));
                set.add(src.offset(0, 0, dz));
                // A sprinted diagonal can carry the body one cell past dest along the move
                // axis before it settles — anchor that overshoot cell too, so the relocalizer
                // holds the index there instead of reading "off path" while arrived() fights
                // the body back toward dest (the DIAGONAL bang-bang jitter, P1-3).
                set.add(dest.offset(Integer.signum(dx), 0, Integer.signum(dz)));
            }
            case DESCEND, FALL -> {
                // Step horizontally into the column at source height, then fall.
                BlockPos entry = new BlockPos(dest.getX(), src.getY(), dest.getZ());
                set.add(entry);
                for (int y = src.getY() - 1; y > dest.getY(); y--) {
                    set.add(new BlockPos(dest.getX(), y, dest.getZ()));
                }
                // Landing tolerance: residual momentum / knockback routinely lands a
                // multi-block drop one cell off the planned column, and without a tolerance
                // the relocalizer sees "off path" and burns the whole AWAY_BUDGET before
                // replanning (Baritone judges fall landings by flat distance, same idea).
                // Restrict that tolerance to a 1×3 strip ALONG the travel direction (dest,
                // one ahead, one behind) rather than a full 3×3 box — the box's off-axis
                // corners overlap a NEIGHBOURING segment's landing box and thrash the
                // relocalizer's index between segments (P2-9). A straight-down drop keeps
                // just dest.
                int fdx = Integer.signum(dest.getX() - src.getX());
                int fdz = Integer.signum(dest.getZ() - src.getZ());
                if (fdx != 0 || fdz != 0) {
                    set.add(dest.offset(fdx, 0, fdz));       // overshot one ahead (carried momentum)
                    set.add(dest.offset(-fdx, 0, -fdz));     // fell one short (braked / knocked back)
                }
            }
            default -> { /* TRAVERSE, PILLAR, DIG_DOWN, FLY: just src + dest */ }
        }
        validPositions = set;
        return set;
    }

    /**
     * True if this movement is driven by explicit jump/impulse inputs rather
     * than steered by the vanilla {@code MoveControl} toward {@link #dest}.
     * Vertical/ballistic moves (PILLAR, and later PARKOUR) must NOT hand a
     * wantedY above the entity to MoveControl — that makes it auto-jump and
     * fight our explicit jump cycle — so the executor drives them by hand.
     */
    public boolean inputDriven() {
        return kind == Kind.PILLAR || kind == Kind.PARKOUR;
    }

}
