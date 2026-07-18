package com.dwinovo.numen.core.pathing.calc;

import com.dwinovo.numen.core.pathing.movement.FlyTunables;
import com.dwinovo.numen.core.pathing.movement.Movement;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * The independent lightweight 3D air search of creative-motion design v1 — the
 * "绕飞" step of the flight decision tree. Deliberately NOT the ground A*: the
 * ground planner's 26+ survival assumptions (canWalkOn, fall damage, scaffold
 * budgets) stay untouched; this is a small bounded best-first search over AIR
 * cells only, run synchronously on the tick thread when — and only when — the
 * direct-fly corridor is obstructed.
 *
 * <ul>
 *   <li>26-neighbourhood, Euclidean edge cost in ticks (distance × ticks/block
 *       at the current cruise speed);</li>
 *   <li>sky preference: upward edges discounted
 *       ({@link FlyTunables#FLY_COST_UP_DISCOUNT}), downward surcharged — the
 *       search naturally routes OVER obstacles, the blueprint's +y 折扣;</li>
 *   <li>water cells flyable but ×{@link FlyTunables#FLY_COST_WATER_MULT}
 *       (creative flight works underwater, just slow and ugly — only taken when
 *       no air route exists); lava/fire never;</li>
 *   <li>bounded by a Chebyshev {@code radius} box around the start and a node
 *       cap, so a worst case costs a couple of milliseconds, once, on an
 *       obstruction event — not per tick;</li>
 *   <li>result is a {@link Path} of {@link Movement.Kind#FLY} edges (partial
 *       when the goal wasn't reached but real progress was made), so the
 *       existing path viz renders the detour for the owner unchanged.</li>
 * </ul>
 *
 * <p>Purely mechanical: world reads via {@code getCollisionShape}/fluid state on
 * the live level (main thread, within {@code radius} ≤ 32 of the body — loaded
 * terrain). No LLM anywhere near this.
 */
public final class FlyPlanner {

    private FlyPlanner() {}

    /** The 26 neighbour offsets of a cell. */
    private static final int[][] NEIGHBOURS;
    static {
        List<int[]> n = new ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) {
                        n.add(new int[]{dx, dy, dz});
                    }
                }
            }
        }
        NEIGHBOURS = n.toArray(new int[0][]);
    }

    private static final class Node implements Comparable<Node> {
        final BlockPos pos;
        double g;
        double f;
        Node parent;
        boolean closed;

        Node(BlockPos pos) {
            this.pos = pos;
        }

        @Override public int compareTo(Node o) {
            return Double.compare(f, o.f);
        }
    }

    /**
     * Search for an air route from {@code start} toward {@code aim}, succeeding at
     * any cell where {@code goal.isAt} holds (or at {@code aim} itself). Returns a
     * {@link Path} of FLY movements — {@code partial} when the search ran out of
     * radius/nodes but its best frontier cell is at least
     * {@link FlyTunables#MIN_AVOID_PROGRESS} blocks closer to the aim than the
     * start (fly it, then re-assess from there) — or {@code null} when no useful
     * route exists inside the box.
     *
     * @param ticksPerBlock cruise cost of one block of flight (1/cruiseSpeed),
     *                      the blueprint's FLY 1.0~2.0 tick/格 band.
     */
    public static Path plan(Level level, BlockPos start, BlockPos aim, NavGoal goal,
                            int radius, int nodeCap, double ticksPerBlock) {
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Node> nodes =
                new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>();

        Node startNode = new Node(start.immutable());
        startNode.g = 0.0;
        startNode.f = heuristic(start, aim, ticksPerBlock);
        nodes.put(start.asLong(), startNode);
        open.add(startNode);

        Node best = startNode;
        double bestH = startNode.f;
        int expanded = 0;

        while (!open.isEmpty() && expanded < nodeCap) {
            Node cur = open.poll();
            if (cur.closed) continue;   // stale duplicate queue entry
            cur.closed = true;
            expanded++;

            if (goal.isAt(cur.pos) || cur.pos.equals(aim)) {
                return reconstruct(start, cur, false);
            }
            double h = heuristic(cur.pos, aim, ticksPerBlock);
            if (h < bestH) {
                bestH = h;
                best = cur;
            }

            for (int[] d : NEIGHBOURS) {
                BlockPos np = cur.pos.offset(d[0], d[1], d[2]);
                if (Math.abs(np.getX() - start.getX()) > radius
                        || Math.abs(np.getY() - start.getY()) > radius
                        || Math.abs(np.getZ() - start.getZ()) > radius) {
                    continue;
                }
                if (np.getY() < level.getMinY() || np.getY() + 1 > level.getMaxY()) {
                    continue;
                }
                if (!bodyFlyable(level, np)) {
                    continue;
                }
                // No diagonal corner-cutting: every single-axis projection of the step
                // must itself fit the body (a 0.6-wide body can't slip between two
                // solid corners even though both cells are air).
                if (!projectionsClear(level, cur.pos, d)) {
                    continue;
                }
                double edge = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]) * ticksPerBlock;
                if (d[1] > 0) edge *= FlyTunables.FLY_COST_UP_DISCOUNT;
                else if (d[1] < 0) edge *= FlyTunables.FLY_COST_DOWN_SURCHARGE;
                if (isWaterish(level, np)) edge *= FlyTunables.FLY_COST_WATER_MULT;

                double ng = cur.g + edge;
                Node node = nodes.get(np.asLong());
                if (node == null) {
                    node = new Node(np.immutable());
                    node.g = ng;
                    node.f = ng + heuristic(np, aim, ticksPerBlock);
                    node.parent = cur;
                    nodes.put(np.asLong(), node);
                    open.add(node);
                } else if (ng < node.g - 0.001 && !node.closed) {
                    node.g = ng;
                    node.f = ng + heuristic(np, aim, ticksPerBlock);
                    node.parent = cur;
                    open.add(node);   // duplicate entry; stale one skipped via `closed`
                }
            }
        }

        // Frontier drained or node cap hit — take the best-so-far cell if it's real progress.
        double startDist = Math.sqrt(start.distSqr(aim));
        double bestDist = Math.sqrt(best.pos.distSqr(aim));
        if (best != startNode && startDist - bestDist >= FlyTunables.MIN_AVOID_PROGRESS) {
            return reconstruct(start, best, true);
        }
        return null;
    }

    /** Admissible-ish bound: Euclidean distance at the cheapest (discounted-up) rate. */
    private static double heuristic(BlockPos from, BlockPos aim, double ticksPerBlock) {
        return Math.sqrt(from.distSqr(aim)) * ticksPerBlock * FlyTunables.FLY_COST_UP_DISCOUNT;
    }

    private static Path reconstruct(BlockPos start, Node end, boolean partial) {
        List<Node> chain = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent) {
            chain.add(n);
        }
        java.util.Collections.reverse(chain);
        List<Movement> movements = new ArrayList<>(Math.max(1, chain.size() - 1));
        for (int i = 1; i < chain.size(); i++) {
            Node prev = chain.get(i - 1);
            Node cur = chain.get(i);
            movements.add(new Movement(Movement.Kind.FLY, prev.pos, cur.pos,
                    cur.g - prev.g, List.of(), null));
        }
        if (movements.isEmpty()) {
            return null;   // end == start: no useful route
        }
        return new Path(start, end.pos, movements, partial);
    }

    /** The body (feet cell + head cell) fits and may fly here. */
    public static boolean bodyFlyable(BlockGetter level, BlockPos feet) {
        return flyableCell(level, feet) && flyableCell(level, feet.above());
    }

    /**
     * One cell a flying body may occupy: collision-free air (grass, torches, …
     * included), or WATER (penalised by cost, never preferred). Lava and fire are
     * refused outright — a creative body doesn't die, but flying through them is
     * wrong in every other way; cobweb/powder-snow/bubble columns trap or shove
     * the body, so they're solid to the planner too.
     */
    private static boolean flyableCell(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) {
            return state.getFluidState().is(FluidTags.WATER);
        }
        if (state.getBlock() instanceof BaseFireBlock
                || state.is(Blocks.COBWEB)
                || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.BUBBLE_COLUMN)) {
            return false;
        }
        return state.getCollisionShape(level, pos, CollisionContext.empty()).isEmpty();
    }

    /** Either body cell in water (for the cost surcharge). */
    private static boolean isWaterish(BlockGetter level, BlockPos feet) {
        return level.getBlockState(feet).getFluidState().is(FluidTags.WATER)
                || level.getBlockState(feet.above()).getFluidState().is(FluidTags.WATER);
    }

    /** Every nonzero single-axis projection of step {@code d} from {@code from}
     *  must fit the body — the 3D no-corner-cut rule. */
    private static boolean projectionsClear(BlockGetter level, BlockPos from, int[] d) {
        int axes = (d[0] != 0 ? 1 : 0) + (d[1] != 0 ? 1 : 0) + (d[2] != 0 ? 1 : 0);
        if (axes <= 1) return true;   // straight step — the destination check suffices
        if (d[0] != 0 && !bodyFlyable(level, from.offset(d[0], 0, 0))) return false;
        if (d[1] != 0 && !bodyFlyable(level, from.offset(0, d[1], 0))) return false;
        if (d[2] != 0 && !bodyFlyable(level, from.offset(0, 0, d[2]))) return false;
        return true;
    }
}
