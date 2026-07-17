package com.dwinovo.numen.core.autonomy;

/**
 * Pure, Minecraft-free decision helpers for the idle-autonomy layer (spinal cord L3,
 * see {@code docs/idle-autonomy-L3.md}). Deliberately dependency-free: the arbitration
 * gate, tether-radius arithmetic and rhythm/journal bookkeeping are the load-bearing
 * "constitution" (§3) of the layer, so they are kept isolated here where they can be
 * asserted by a bare {@code javac} harness without a running game.
 *
 * <p>Every method is a total function of its arguments — no state, no clock, no world.
 * {@link AutonomyScheduler} owns all the Minecraft-side wiring and calls into this.
 */
final class AutonomyLogic {

    private AutonomyLogic() {}

    // ============================================================ arbitration gate (§3)

    /**
     * The L3 constitution's priority ladder: <em>reflex &gt; owner task &gt; brain turn in flight
     * &gt; idle autonomy</em>. If ANY higher activity is present the autonomy layer must stand down
     * for this tick and abandon any unfinished idle work (no cleanup debt). Returns true ⇒ yield.
     *
     * @param reflexOwnsBody       a survival reflex episode is driving the body ({@code Reflexes.ownsBody})
     * @param ownerTaskActive      a queued/running owner task is driving the body
     * @param brainOrPendingActive the brain has a turn in flight, or a tool call is pending
     */
    static boolean shouldYield(boolean reflexOwnsBody, boolean ownerTaskActive, boolean brainOrPendingActive) {
        return reflexOwnsBody || ownerTaskActive || brainOrPendingActive;
    }

    /**
     * Quiet-settle guard: after the last tick on which higher activity was seen, the autonomy layer
     * only engages once the body has stayed quiet for the full settle window. This bridges the brain's
     * think→act gaps (a turn in flight is not directly observable server-side, but the brain keeps
     * queuing tool calls, each of which refreshes {@code lastBusyTick}) and the brief queue→run handoff.
     */
    static boolean settled(long now, long lastBusyTick, int settleTicks) {
        return now - lastBusyTick >= settleTicks;
    }

    // ============================================================ tether (§2 master ruling)

    /** Tether radius: master-locked at {@code onlineRadius} when the owner anchors, else {@code offlineRadius}. */
    static int tetherRadius(boolean ownerPresent, int onlineRadius, int offlineRadius) {
        return ownerPresent ? onlineRadius : offlineRadius;
    }

    /** Horizontal (x/z) containment: is {@code (px,pz)} within {@code radius} of the anchor {@code (ax,az)}? */
    static boolean withinTether(double ax, double az, double px, double pz, double radius) {
        double dx = px - ax, dz = pz - az;
        return dx * dx + dz * dz <= radius * radius;
    }

    /**
     * Clamp a candidate stroll target to the tether disc: a point outside {@code radius} of the anchor
     * is pulled back onto the rim (direction preserved). This keeps every wander leg legal, and — because
     * an out-of-bounds body's anchor-relative targets all resolve inward — realises the ruling
     * "越界则下一目标拉回半径内". Returns {@code [x, z]}.
     */
    static double[] clampToTether(double ax, double az, double tx, double tz, double radius) {
        double dx = tx - ax, dz = tz - az;
        double d2 = dx * dx + dz * dz;
        if (d2 <= radius * radius || d2 == 0.0) return new double[]{tx, tz};
        double k = radius / Math.sqrt(d2);
        return new double[]{ax + dx * k, az + dz * k};
    }

    // ============================================================ rhythm (§3: 灵动≠忙碌)

    /** True once the current rest/cooldown has elapsed and a new single action may start. */
    static boolean restElapsed(long now, long restUntil) {
        return now >= restUntil;
    }

    /**
     * Deterministic rest-duration picker in {@code [minTicks, maxTicks]} from a unit fraction {@code [0,1)}
     * (the enforced between-actions cooldown — "漫步一程后停 5~10 秒").
     */
    static long restDuration(double frac, long minTicks, long maxTicks) {
        if (frac < 0.0) frac = 0.0;
        else if (frac >= 1.0) frac = 0.999999;
        return minTicks + (long) (frac * (maxTicks - minTicks + 1));
    }

    // ============================================================ dream-journal (§0 informs-never-consults)

    /**
     * Should the accumulated autonomy session be flushed to the dream-journal now? Flush on interruption
     * (an owner turn/reflex seized the body) or once it has run long enough — but ONLY if it actually
     * accomplished something. Zero-append discipline: a stretch that did nothing produces no event.
     */
    static boolean shouldFlushJournal(boolean sessionActive, boolean interrupted,
                                      long now, long sessionStart, long maxSessionTicks,
                                      int strollLegs, int pickedCount) {
        if (!sessionActive) return false;
        if (strollLegs <= 0 && pickedCount <= 0) return false;   // nothing accomplished → no append
        return interrupted || (now - sessionStart >= maxSessionTicks);
    }
}
