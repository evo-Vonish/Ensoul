package com.dwinovo.numen.core.pathing.exec;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, Minecraft-free geometry for the motor-skill library — the pure
 * decision core behind place_block's stance repositioning and the vertical
 * shaft climbers (pillar_up / escape_to_surface). Kept free of any {@code net.minecraft}
 * type on purpose: the load-bearing decisions (which stance to walk to, whether to
 * rise / dig / sidestep / stop) are exercised by scratchpad assertions against a
 * hand-built world, so the real code is what the tests cover — no drift between a
 * "test copy" and the shipped logic.
 *
 * <p>World facts enter through the small {@link Cell} predicate (block queries) and
 * a handful of enum inputs the callers classify with {@code BlockHelper}. Everything
 * here is a pure function of those.
 */
public final class MotorGeometry {

    private MotorGeometry() {}

    /** A block-cell predicate injected by the caller (backed by the live level). */
    @FunctionalInterface
    public interface Cell {
        boolean test(int x, int y, int z);
    }

    /** A feet cell — where the body stands (it occupies this cell and the one above). */
    public record Foot(int x, int y, int z) {
        public int distSqrTo(int ox, int oy, int oz) {
            int dx = x - ox, dy = y - oy, dz = z - oz;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    /** The four cardinal horizontal offsets, in a stable N,S,E,W order. */
    private static final int[][] HORIZONTAL = {
            {0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {-1, 0, 0}};

    /** The eight horizontal offsets (cardinals first, then diagonals) — stance neighbours of a column. */
    private static final int[][] HORIZONTAL8 = {
            {0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {-1, 0, 0},
            {1, 0, -1}, {1, 0, 1}, {-1, 0, -1}, {-1, 0, 1}};

    /**
     * Standing spots from which a real player could place a block at {@code (tx,ty,tz)},
     * ordered nearest-first from {@code (cx,cy,cz)} (the body's current feet).
     *
     * <p>A candidate is a horizontal-neighbour column of the target, with feet at either
     * the target's own Y or one below it (so the crouched eye looks level-or-down at the
     * block's side/top face). It qualifies iff {@code standable} — a solid floor beneath,
     * body clearance at the feet and head cells. Because candidates are horizontal
     * neighbours, the body's footprint {@code {s, s.above()}} can never contain the target
     * cell, so "you're standing in the cell you're trying to fill" is resolved simply by
     * walking to any candidate.
     *
     * <p>Order: ascending squared distance to the current feet, then cardinal stances
     * before diagonal ones (a cleaner line to a side face), then a lexicographic tiebreak
     * — fully deterministic, no dependence on iteration/hash order.
     */
    public static List<Foot> stanceCandidates(int tx, int ty, int tz,
                                              Cell standable, int cx, int cy, int cz) {
        List<Foot> out = new ArrayList<>(16);
        for (int footDy = 0; footDy >= -1; footDy--) {           // feet at target Y, then one below
            for (int[] h : HORIZONTAL8) {
                int sx = tx + h[0];
                int sy = ty + footDy;
                int sz = tz + h[2];
                if (standable.test(sx, sy, sz)) {
                    out.add(new Foot(sx, sy, sz));
                }
            }
        }
        out.sort((a, b) -> {
            int da = a.distSqrTo(cx, cy, cz);
            int db = b.distSqrTo(cx, cy, cz);
            if (da != db) return Integer.compare(da, db);
            boolean aCard = isCardinalTo(a, tx, tz);
            boolean bCard = isCardinalTo(b, tx, tz);
            if (aCard != bCard) return aCard ? -1 : 1;           // cardinal before diagonal
            if (a.x() != b.x()) return Integer.compare(a.x(), b.x());
            if (a.y() != b.y()) return Integer.compare(b.y(), a.y());  // higher feet first
            return Integer.compare(a.z(), b.z());
        });
        return out;
    }

    /** Is this stance directly N/S/E/W of the target column (vs a diagonal)? */
    private static boolean isCardinalTo(Foot s, int tx, int tz) {
        return (s.x() == tx) ^ (s.z() == tz);   // exactly one axis aligned
    }

    // ---- vertical shaft climb (pillar_up / escape_to_surface) ----

    /** What occupies the cell the body's HEAD must pass through to rise one block. */
    public enum Head {
        /** Passable — a clear rung to jump into. */
        CLEAR,
        /** A fluid (lava/water) — never dig it; sidestep or abort. */
        FLUID,
        /** A solid but breakable block — dig it away, then rise. */
        BREAKABLE,
        /** Bedrock / otherwise unbreakable — can't go up through it here. */
        UNBREAKABLE
    }

    /** The next action a shaft climber should take this rung. */
    public enum Step {
        /** Jump + place a scaffold block underfoot to rise one. */
        RISE,
        /** Break the block overhead first. */
        DIG,
        /** Overhead is fluid/unbreakable but a safe neighbour column exists — shift there and continue. */
        SIDESTEP,
        /** Target height reached. */
        DONE,
        /** Overhead fluid and nowhere safe to sidestep. */
        FAIL_FLUID_NO_SIDESTEP,
        /** Overhead unbreakable and nowhere safe to sidestep. */
        FAIL_UNBREAKABLE,
        /** A clear rung above but no scaffold block to stand up on. */
        FAIL_NO_SCAFFOLD
    }

    /**
     * The deterministic per-rung decision shared by pillar_up and escape_to_surface.
     * {@code atTarget} short-circuits to {@link Step#DONE}. Otherwise the overhead
     * classification drives it: a fluid or unbreakable ceiling yields {@link Step#SIDESTEP}
     * when {@code sidestepAvailable} (escape_to_surface offers this; pillar_up never does,
     * so it fails fast with a precise reason), a breakable ceiling yields {@link Step#DIG},
     * and a clear ceiling yields {@link Step#RISE} only when {@code haveScaffold}.
     */
    public static Step shaftStep(boolean atTarget, Head head,
                                 boolean haveScaffold, boolean sidestepAvailable) {
        if (atTarget) return Step.DONE;
        return switch (head) {
            case FLUID -> sidestepAvailable ? Step.SIDESTEP : Step.FAIL_FLUID_NO_SIDESTEP;
            case UNBREAKABLE -> sidestepAvailable ? Step.SIDESTEP : Step.FAIL_UNBREAKABLE;
            case BREAKABLE -> Step.DIG;
            case CLEAR -> haveScaffold ? Step.RISE : Step.FAIL_NO_SCAFFOLD;
        };
    }

    /** Cardinal neighbour columns of {@code (x,z)} in stable order — the sidestep search order. */
    public static int[][] sidestepColumns(int x, int z) {
        int[][] cols = new int[HORIZONTAL.length][2];
        for (int i = 0; i < HORIZONTAL.length; i++) {
            cols[i][0] = x + HORIZONTAL[i][0];
            cols[i][1] = z + HORIZONTAL[i][2];
        }
        return cols;
    }
}
