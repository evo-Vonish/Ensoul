package com.dwinovo.numen.core.perception.region;

/**
 * The pure, Minecraft-free trigger predicate for the ③ move-settlement hook: after a world-action task
 * settles, does the body's change since the last regional observation warrant re-running
 * {@link RegionCognition#observe}? True when it has relocated at least {@link #MIN_MOVE} blocks OR at
 * least one block was mined in the interim.
 *
 * <p>Isolated with no Minecraft dependencies so this load-bearing gate can be asserted by a bare-javac
 * unit test (凡承重必机械 — the gate is code, not the model's judgement). The downstream
 * {@code RegionCognition.observe} still enforces zero-append, so a {@code true} here only guarantees a
 * (cheap) re-scan, never an unconditional log append.
 */
public final class MoveSettlePredicate {

    /** Displacement (blocks) since the last observation that forces a re-observe. */
    public static final double MIN_MOVE = 5.0;

    private MoveSettlePredicate() {}

    /**
     * @param movedSqr     squared Euclidean displacement since the last observation (blocks²)
     * @param worldMutated whether the settled task mined/placed blocks (the "挖/放" branch — evaluated
     *                     mechanically from the tool class, see {@code MoveSettlement}; zero-append then
     *                     drops it back to nothing if the region is in fact unchanged)
     * @return whether {@code observe()} should run now
     */
    public static boolean shouldReobserve(double movedSqr, boolean worldMutated) {
        return movedSqr >= MIN_MOVE * MIN_MOVE || worldMutated;
    }
}
