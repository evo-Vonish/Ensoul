package com.dwinovo.numen.core.pathing.exec;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.calc.AStar;
import com.dwinovo.numen.core.pathing.calc.AStarSearch;
import com.dwinovo.numen.core.pathing.calc.NavContext;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.calc.Path;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.core.pathing.viz.PathVizPublisher;
import com.dwinovo.numen.core.pathing.util.PathSettings;
import net.minecraft.core.BlockPos;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Plan → execute → replan driver for a companion {@link NumenPlayer} body.
 * The player-body twin of {@code Navigator}: same path-while-moving loop (walk
 * the current path; precompute the continuation of a partial path so there's no
 * planning pause at a segment boundary; re-root on a moving goal or after an
 * off-path replan) but executing through {@link PlayerPathExecutor}. The A*
 * planner ({@link NavContext}/{@link AStar}) is body-neutral and reused as-is.
 *
 * <p>Internally the goal is a {@link NavGoal} so callers can target a single
 * cell ({@link #PlayerNav(NumenPlayer, BlockPos, double, BooleanSupplier)}) or
 * a richer goal — a {@link NavGoal#composite composite} ore field, a mining
 * stance — via {@link #toGoal}.
 *
 * <p><b>Creative flight (design v1, wave 2):</b> a body that may fly
 * ({@code mayfly}) navigates by {@link FlyPathExecutor} instead — flight is the
 * creative-first locomotion for EVERY nav caller (move_to, mining approach,
 * item collection, …) with zero caller changes. The switch is seamless both
 * ways: creative granted mid-walk takes to the air at the next tick; mayfly
 * revoked mid-flight (or an honest aerial failure, e.g. a protected player
 * build in the way) falls back to this ground pipeline. Survival navigation is
 * bit-for-bit untouched — every flight branch sits behind the mayfly gate.
 */
public final class PlayerNav {

    public enum Status { RUNNING, ARRIVED, FAILED }

    private static final int MAX_REPLANS = 40;
    private static final double GOAL_MOVED_SQR = 4.0;

    private final NumenPlayer player;
    private final Supplier<NavGoal> goalSupplier;
    private final double speed;
    private final BooleanSupplier reached;
    private final AStar astar = new AStar();

    private BlockPos plannedCenter;
    private PlayerPathExecutor current;

    // ---- creative flight (creative-motion design v1, wave 2) ----
    /** The flight executor when the body may fly (creative). Null → ground pipeline.
     *  Every flight branch in this class is gated behind {@link FlyPathExecutor#available}
     *  (mayfly), so survival navigation is untouched by construction. */
    private FlyPathExecutor flight;
    /** Set when an aerial attempt terminally failed for THIS nav — the ground pipeline
     *  takes over and we don't re-enter the air (a fresh nav may try again). */
    private boolean flightDisabled;
    /** The aerial failure, preserved so a subsequent ground failure can report both. */
    private String flightFailNote;

    // A* now runs on the planner pool, not stepped on the tick thread. We hold the future (polled each
    // tick) plus the search object itself (to cancel it on replan/stop so a stale worker stops wasting
    // CPU). One in-flight search at a time for the main path, one for the precomputed next segment.
    private java.util.concurrent.CompletableFuture<Path> searchFuture;
    private AStarSearch searchObj;
    private java.util.concurrent.CompletableFuture<Path> nextFuture;
    private AStarSearch nextObj;
    private PlayerPathExecutor pendingNext;
    private Path pendingPathForViz;

    /** Packed positions of the path we're currently executing — fed to the next
     *  search as Baritone's {@code Favoring} so a replan reuses this route (damps
     *  the flip-flopping a from-scratch replan would otherwise cause). */
    private it.unimi.dsi.fastutil.longs.LongSet previousPathHashes =
            it.unimi.dsi.fastutil.longs.LongSets.emptySet();

    /** Cells to highlight in the overlay; null → just the path's destination.
     *  The mining task sets this to its whole known-ore field (Baritone boxes
     *  every GoalComposite member). */
    private Supplier<java.util.List<BlockPos>> highlights;

    /** Highlight these cells in the path overlay (e.g. the full ore field). */
    public void setHighlights(Supplier<java.util.List<BlockPos>> highlights) {
        this.highlights = highlights;
    }

    private void publishViz(Path cut) {
        java.util.List<BlockPos> targets =
                highlights != null ? highlights.get() : java.util.List.of(cut.end);
        PathVizPublisher.publish(player, cut, targets);
    }

    private int replans = 0;
    private String failReason = "target unreachable";

    /** Walk to a single cell. */
    public PlayerNav(NumenPlayer player, BlockPos goal, double speed, BooleanSupplier reached) {
        this(player, () -> resolveBlockGoal(player, goal), speed, reached, true);
    }

    /** Walk to a (possibly moving) single cell. */
    public PlayerNav(NumenPlayer player, Supplier<BlockPos> goalSupplier, double speed,
                     BooleanSupplier reached) {
        this(player, () -> {
            BlockPos g = goalSupplier.get();
            return g == null ? null : resolveBlockGoal(player, g);
        }, speed, reached, true);
    }

    /** Walk toward an arbitrary {@link NavGoal} (composite ore field, mining stance, …). */
    public static PlayerNav toGoal(NumenPlayer player, Supplier<NavGoal> goalSupplier,
                                   double speed, BooleanSupplier reached) {
        return new PlayerNav(player, goalSupplier, speed, reached, true);
    }

    private PlayerNav(NumenPlayer player, Supplier<NavGoal> goalSupplier, double speed,
                      BooleanSupplier reached, boolean marker) {
        this.player = player;
        this.goalSupplier = goalSupplier;
        this.speed = speed;
        this.reached = reached;
        // 创造态首选飞行 (design v1): a body that MAY fly navigates by air; the ground
        // A* pipeline is the survival path AND the fallback when the air honestly fails.
        if (FlyPathExecutor.available(player)) {
            flight = newFlight();
        } else {
            startFreshSearch();
        }
    }

    private FlyPathExecutor newFlight() {
        return new FlyPathExecutor(player, goalSupplier, speed, this::publishViz);
    }

    /** A cell goal: exact if standable, else reach within 2 (mirrors move_to arrival). */
    private static NavGoal resolveBlockGoal(NumenPlayer player, BlockPos bp) {
        return BlockHelper.canWalkThrough(player.level(), bp)
                ? NavGoal.exact(bp)
                : NavGoal.near(bp, 2.0);
    }

    public Status tick() {
        if (reached.getAsBoolean()) return Status.ARRIVED;

        // Creative granted mid-journey (owner switched the body's game mode): flight is
        // the creative-first locomotion, and taking over is safe from ANY ground state —
        // setting flying arrests even a mid-air fall — so switch at once. The reverse
        // hand-off (mayfly revoked) comes back through Status.UNAVAILABLE below.
        if (flight == null && !flightDisabled && FlyPathExecutor.available(player)) {
            if (current != null) {
                current.stop();
                current = null;
            }
            discardPrecompute();
            cancelSearch();
            flight = newFlight();
        }
        if (flight != null) {
            return tickFlight();
        }

        if (current == null) {
            return advanceFreshSearch();
        }

        NavGoal liveGoal = goalSupplier.get();
        if (liveGoal == null) {
            failReason = "target lost";
            return Status.FAILED;
        }
        if (plannedCenter != null && liveGoal.center().distSqr(plannedCenter) > GOAL_MOVED_SQR) {
            discardPrecompute();
            return restartFresh(false);
        }

        maybePrecompute();
        advancePrecompute();

        switch (current.tick()) {
            case RUNNING -> { return Status.RUNNING; }
            case ARRIVED -> {
                // A COMPLETE path reached its end. A complete path is never precomputed
                // (precompute is partial-only), so this resolves to a budgeted fresh search
                // only when the goal predicate still isn't satisfied (goal moved, or the
                // arrival check is stricter than the path end) — a genuine replan.
                replans = 0;
                return handoffOrRestart(true);
            }
            case SEGMENT_DONE -> {
                // P0-1: a PARTIAL segment was walked cleanly to its end — normal forward
                // progression, NOT a replan. Hand off to the precomputed continuation without
                // halting (seamless, identical to an arrival hand-off, so motion stays
                // continuous), or — if the precompute isn't ready yet — start the next search
                // fresh but DON'T charge it against the replan budget. The old code returned
                // NEEDS_REPLAN at every partial-segment boundary, which discarded the ready
                // precompute (re-running the same search) AND inflated replans until a long,
                // legitimately-multi-segment journey falsely "gave up after MAX_REPLANS".
                replans = 0;
                return handoffOrRestart(false);
            }
            case NEEDS_REPLAN -> {
                discardPrecompute();
                return restartFresh(true);
            }
            case FAILED -> {
                return Status.FAILED;
            }
        }
        return Status.RUNNING;
    }

    /**
     * One flight tick, mapped onto the nav contract. RUNNING passes through; ARRIVED
     * with the caller's {@code reached} still false (tick() checked it first) hovers
     * and re-polls — a moving goal re-arms the executor by itself, and a permanently
     * stricter predicate is budgeted against {@link #MAX_REPLANS} so the body falls
     * back to walking instead of hovering forever. UNAVAILABLE (mayfly revoked
     * mid-nav) hands off to the ground pipeline seamlessly from wherever the body
     * is; FAILED preserves the honest aerial reason and lets the ground pipeline
     * have its try (creative ground pathing — wave 1 — still works).
     */
    private Status tickFlight() {
        switch (flight.tick()) {
            case RUNNING -> {
                return Status.RUNNING;
            }
            case ARRIVED -> {
                if (reached.getAsBoolean()) return Status.ARRIVED;
                if (++replans >= MAX_REPLANS) {
                    flightFailNote = "arrived by air but the goal predicate never satisfied";
                    abandonFlight();
                }
                return Status.RUNNING;
            }
            case UNAVAILABLE -> {
                flight = null;                 // not disabled: re-enters if mayfly returns
                startFreshSearch();
                return Status.RUNNING;
            }
            case FAILED -> {
                failReason = flight.failReason();
                flightFailNote = flight.failReason();
                abandonFlight();
                return Status.RUNNING;
            }
        }
        return Status.RUNNING;
    }

    /** Terminal aerial failure for THIS nav: hand the body to the ground pipeline.
     *  Flying is RELEASED here — with it on, gravity is off and the ground executor's
     *  physics (falls, jumps, onGround gates) can never engage; a creative body is
     *  invulnerable, so the drop to the ground is safe, and the ground planner's
     *  replan budget absorbs the landing. (The arrival hover keeps flying ON — the
     *  blueprint's 悬停不摘 — this release is only the failure fallback.) */
    private void abandonFlight() {
        if (flight != null) {
            flight.stop();
            flight = null;
        }
        flightDisabled = true;
        var abilities = player.getAbilities();
        if (abilities.flying) {
            abilities.flying = false;
            player.onUpdateAbilities();
        }
        startFreshSearch();
    }

    /**
     * Shared tail for a segment/path end (P0-1). Goal reached → ARRIVED; else hand off to the
     * precomputed continuation WITHOUT halting — calling {@code current.stop()} zeroes the
     * inputs for a tick and hitches at every boundary; pendingNext takes over the inputs on its
     * first tick, so motion stays continuous; else cancel any still-in-flight precompute and
     * start the next search fresh. {@code budgeted} charges that fresh search against the replan
     * budget: {@code true} for a complete-path re-search (the goal wasn't actually satisfied),
     * {@code false} for normal partial-segment progression (segment completion is progress, not
     * a failure — a runaway is bounded by the task-level deadline, not the replan cap).
     */
    private Status handoffOrRestart(boolean budgeted) {
        if (reached.getAsBoolean()) return Status.ARRIVED;
        if (pendingNext != null) {
            current = pendingNext;
            pendingNext = null;
            if (pendingPathForViz != null) {
                publishViz(pendingPathForViz);
                pendingPathForViz = null;
            }
            return Status.RUNNING;
        }
        discardPrecompute();   // no continuation ready — drop any stale in-flight one, then re-search
        return restartFresh(budgeted);
    }

    /** Frozen context for a SEARCH — snapshot inventory + an immutable loaded-chunk view, safe to read
     *  off the tick thread. Ensure the level's snapshot exists first so the view is never the live
     *  read-through fallback (which a worker thread mustn't touch). */
    private NavContext searchContext() {
        if (player.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            com.dwinovo.numen.core.pathing.cache.PathCaches.ensureSnapshot(sl, player.blockPosition());
        }
        return NavContext.forSearch(player.level(), player.getInventory());
    }

    /** Off-thread when the context is frozen (the normal case); on the main thread otherwise — a
     *  context whose view is the live read-through ({@code safeForThreadedUse == false}) must NOT run
     *  on a worker. The latter is a rare safety net (e.g. no chunk snapshot yet); it returns an
     *  already-completed future so the polling code is identical. */
    private java.util.concurrent.CompletableFuture<Path> dispatch(NavContext ctx, AStarSearch s) {
        return ctx.safeForThreadedUse ? runAsync(s) : java.util.concurrent.CompletableFuture.completedFuture(runToCompletion(s));
    }

    /** Run a search to completion on the planner pool (off the tick thread). The node cap inside the
     *  search bounds it, so one {@code step} call runs the whole thing. */
    private static java.util.concurrent.CompletableFuture<Path> runAsync(AStarSearch s) {
        return com.dwinovo.numen.core.pathing.calc.PathPlannerPool.submit(() -> runToCompletion(s));
    }

    /** One {@code step} to the node cap; a thrown planner bug yields no path rather than wedging the
     *  companion (or, off-thread, completing the future exceptionally). */
    private static Path runToCompletion(AStarSearch s) {
        try {
            s.step(Integer.MAX_VALUE);
            return s.result();
        } catch (Throwable t) {
            com.dwinovo.numen.Constants.LOG.error("path search failed", t);
            return null;
        }
    }

    /** Live context for EXECUTION re-costing (main thread; reads current world + inventory). */
    private NavContext executionContext() {
        return NavContext.forExecution(player.level(), player.getInventory());
    }

    private void startFreshSearch() {
        NavGoal g = goalSupplier.get();
        plannedCenter = (g == null) ? null : g.center();
        if (g == null) {
            searchFuture = null;
            searchObj = null;
            return;
        }
        NavContext ctx = searchContext();
        AStarSearch s = astar.newSearch(ctx,
                BlockHelper.playerFeet(player.level(), player.getX(), player.getY(), player.getZ()),
                g, previousPathHashes);
        searchObj = s;
        searchFuture = dispatch(ctx, s);
    }

    /** Cancel and forget the in-flight main search (so a stale worker stops and its result is ignored). */
    private void cancelSearch() {
        if (searchObj != null) {
            searchObj.cancel();
            searchObj = null;
        }
        searchFuture = null;
    }

    /** Packed positions (start + every movement dest) of a path — its Favoring set. */
    private static it.unimi.dsi.fastutil.longs.LongSet pathHashes(Path p) {
        var set = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(p.movements.size() + 1);
        set.add(p.start.asLong());
        for (com.dwinovo.numen.core.pathing.movement.Movement m : p.movements) {
            set.add(m.dest.asLong());
        }
        return set;
    }

    private Status advanceFreshSearch() {
        if (searchFuture == null) {
            failReason = "target lost";
            return Status.FAILED;
        }
        if (!searchFuture.isDone()) {
            return Status.RUNNING;   // worker still planning — body waits (it was idle anyway)
        }
        Path path = searchFuture.getNow(null);
        AStarSearch finished = searchObj;   // capture the terminal reason BEFORE clearing it
        searchFuture = null;
        searchObj = null;
        if (path == null || path.isEmpty()) {
            failReason = describeEmptyPath(finished);
            if (flightFailNote != null) {
                // The air was tried first and failed for its own honest reason (e.g. a
                // protected player build) — report both, not just the ground's view.
                failReason += " (air route also failed: " + flightFailNote + ")";
            }
            return reached.getAsBoolean() ? Status.ARRIVED : Status.FAILED;
        }
        Path cut = path.staticCutoff();
        current = new PlayerPathExecutor(player, cut, speed, this::executionContext);
        previousPathHashes = pathHashes(cut);   // favor this route on the next replan
        publishViz(cut);
        return Status.RUNNING;
    }

    private Status restartFresh(boolean budgeted) {
        if (current != null) {
            current.stop();
            current = null;
        }
        cancelSearch();   // abandon any in-flight main search before dispatching a new one
        if (budgeted && replans++ >= MAX_REPLANS) {
            failReason = "gave up after " + MAX_REPLANS + " replans";
            return reached.getAsBoolean() ? Status.ARRIVED : Status.FAILED;
        }
        startFreshSearch();
        return Status.RUNNING;
    }

    private void maybePrecompute() {
        if (nextFuture != null || pendingNext != null) return;
        if (current == null || !current.isPartial()) return;
        // Baritone planAhead: start the next segment once the current one has
        // fewer than planningTickLookahead (150) ticks of travel left.
        if (current.remainingCost() > PathSettings.PLANNING_TICK_LOOKAHEAD) return;
        NavGoal g = goalSupplier.get();
        if (g == null) return;
        plannedCenter = g.center();
        NavContext ctx = searchContext();
        AStarSearch s = astar.newSearch(ctx, current.pathEnd(), g, previousPathHashes);
        nextObj = s;
        nextFuture = dispatch(ctx, s);
    }

    private void advancePrecompute() {
        if (nextFuture == null) return;
        if (!nextFuture.isDone()) return;
        Path np = nextFuture.getNow(null);
        nextFuture = null;
        nextObj = null;
        if (np != null && !np.isEmpty()) {
            Path cut = np.staticCutoff();
            pendingNext = new PlayerPathExecutor(player, cut, speed, this::executionContext);
            pendingPathForViz = cut;
            previousPathHashes = pathHashes(cut);   // the next segment becomes the favored route
        }
    }

    private void discardPrecompute() {
        if (nextObj != null) {
            nextObj.cancel();
            nextObj = null;
        }
        nextFuture = null;
        pendingPathForViz = null;
        if (pendingNext != null) {
            pendingNext.stop();
            pendingNext = null;
        }
    }

    /**
     * ⑤ Split the old three-in-one "no path (obstructed or out of bridging blocks)" message into its real
     * cause, using the finished search's terminal reason:
     * <ul>
     *   <li><b>over budget</b> — A* hit its node cap before reaching the goal (too far / too complex);</li>
     *   <li><b>blocked by X</b> — a concrete break-veto sits on the straight line (stone-with-a-sword,
     *       lava, a functional block …), the actionable case;</li>
     *   <li><b>no scaffold</b> — the frontier drained and the body carries no bridging blocks, so a
     *       gap/pillar could never be crossed;</li>
     *   <li><b>truly no path</b> — the frontier drained and the terrain is genuinely unreachable.</li>
     * </ul>
     */
    private static String describeEmptyPath(AStarSearch s) {
        if (s == null) {
            return "no path to target (obstructed or out of bridging blocks)";
        }
        if (s.terminatedByBudget()) {
            return "the target is too far or the route too complex — the pathfinder hit its search budget "
                    + "before finding a way. Move to a nearer waypoint (or scan_blocks first), then retry.";
        }
        String veto = s.diagnose();
        if (veto != null) {
            return "no path: blocked by " + veto + ".";
        }
        if (!s.hasScaffold()) {
            return "no path: the route needs to bridge a gap or pillar up, but you have no scaffolding "
                    + "blocks (cobblestone/dirt) to place. Carry some and retry.";
        }
        return "no path to target: it's walled off or too broken up to reach from here.";
    }

    public String failReason() {
        return failReason;
    }

    public void stop() {
        if (flight != null) {
            // Ends the drive and zeroes the vector; flying stays ON (blueprint: a
            // finished creative move hovers — nothing auto-cancels flight, and the
            // survival-switch descent belongs to the engine-side hook).
            flight.stop();
            flight = null;
        }
        if (current != null) {
            current.stop();
            current = null;
        }
        discardPrecompute();
        cancelSearch();
        InputDriver.halt(player);
        // Release sneak too — a pillar holds it every tick, and unlike Baritone (which
        // resets all inputs per tick) nothing clears it when the path ends, so the body
        // would stay crouched after arriving.
        player.setShiftKeyDown(false);
        PathVizPublisher.clear(player);
    }
}
