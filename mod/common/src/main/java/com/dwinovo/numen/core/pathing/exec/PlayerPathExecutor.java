package com.dwinovo.numen.core.pathing.exec;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.calc.NavContext;
import com.dwinovo.numen.core.pathing.calc.Path;
import com.dwinovo.numen.core.pathing.movement.Movement;
import com.dwinovo.numen.core.pathing.movement.Moves;
import com.dwinovo.numen.core.pathing.util.ActionCosts;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.core.pathing.util.PathSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Walks a computed {@link Path} on a companion {@link NumenPlayer} body. The
 * player-body twin of {@code PathExecutor}: it keeps the same cross-movement
 * spine — re-localization (match real feet against nearby movements'
 * {@code validPositions} and resync the index instead of replanning on every
 * push/overshoot), a per-movement stall budget, and advancement — but the
 * per-movement EXECUTION is input-driving ({@link InputDriver}) + the player's
 * own survival break/place, not the Mob's velocity drives.
 *
 * <p>Each movement runs as: clear its {@code toBreak} obstructions (the native
 * {@link BlockDigger} — {@code handleBlockBreakAction} START/STOP, survival
 * timed, drops fall and the player picks them up natively), place its
 * {@code toPlace} scaffold, then step toward
 * {@code dest} (face it, push forward; hop for ascend/pillar/parkour) until the
 * feet reach it. This is a first cut tuned for compilation + wiring; movement
 * feel gets refined against in-game runs.
 */
public final class PlayerPathExecutor {

    /**
     * Tick outcome. {@link #SEGMENT_DONE} is deliberately distinct from {@link #ARRIVED}
     * (P0-1): a PARTIAL path walked cleanly to its end has finished this SEGMENT — hand off
     * to the precomputed continuation and keep moving — but has NOT reached the goal, and
     * must not be charged against the replan budget the way a genuine off-path / cost cancel
     * ({@link #NEEDS_REPLAN}) is. {@link #ARRIVED} is reserved for a COMPLETE path reaching
     * its end. Both terminal-arrival states are reported only from the "index past the last
     * movement" points; every mid-move bail goes through {@link #replan}/{@code NEEDS_REPLAN}.
     */
    public enum Status { RUNNING, ARRIVED, SEGMENT_DONE, NEEDS_REPLAN, FAILED }

    /** Off-path bands (Baritone MAX_DIST_FROM_PATH 2 / MAX_MAX 3), squared. */
    private static final double SOFT_DIST_SQR =
            PathSettings.MAX_DIST_FROM_PATH * PathSettings.MAX_DIST_FROM_PATH;
    private static final double HARD_DIST_SQR =
            PathSettings.MAX_MAX_DIST_FROM_PATH * PathSettings.MAX_MAX_DIST_FROM_PATH;

    private final NumenPlayer player;
    private final Path path;
    private final double speed;
    /** Builds a fresh world snapshot for per-tick cost re-verification. */
    private final java.util.function.Supplier<NavContext> ctxSupplier;

    private int index = 0;
    private int ticksOnCurrent = 0;
    private int ticksAway = 0;
    private boolean placedThisMove = false;
    /** Set when we sprint-skipped a traverse straight into an ascend (Baritone
     *  sprintableAscend) — the ascend then sprints up instead of jumping from a standstill. */
    private boolean sprintAscend = false;
    /** Set when we sprint-carried into this descend (Baritone canSprintFromDescendInto) —
     *  the descend drives at sprint speed instead of a controlled walk-down. */
    private boolean sprintDescend = false;
    /** When a fall can overshoot forward over the same-direction traverses below it
     *  (Baritone overrideFall), this is the extended aim point: driveFall sprints at it
     *  instead of braking straight down. Recomputed each tick; null = no override. */
    private Vec3 fallOverrideAim = null;
    /** Baritone MovementDescend.numTicks: how many ticks we've actually DRIVEN this descend
     *  (incremented only inside the drive branch, NOT on break/idle ticks) — gates the 20-tick
     *  fakeDest momentum window so a descend that breaks first doesn't burn it before moving. */
    private int descendDriveTicks = 0;
    /** The live edge-sneak scaffold placement for the current move (null when idle). */
    private PlaceManeuver placeManeuver;
    /** Progressive break of path obstructions (shared model with auto-mine). */
    private final BlockDigger digger;
    /** The index whose original cost estimate we cached (Baritone costEstimateIndex). */
    private int costCheckIndex = -1;

    // --- stuck diagnostics (independent of ticksOnCurrent, so index oscillation can't hide a stall) ---
    /** Furthest movement index reached; net forward progress is measured against this. */
    private int maxIndexReached = 0;
    /** Ticks since {@link #maxIndexReached} last grew. Survives advance/snap/skip resets. */
    private int ticksSinceProgress = 0;
    /** One STUCK log per stall episode. */
    private boolean stuckWarned = false;
    /** Warn after this many ticks with no NET forward progress (≈3s) — catches a stall that the
     *  per-movement timeout misses because the index keeps bouncing. */
    private static final int STUCK_WARN_TICKS = 60;
    /** Force a replan after this many ticks of no NET progress (≈6s) — the backstop for an index
     *  thrash that keeps resetting the per-movement timeout so it never fires. */
    private static final int STUCK_REPLAN_TICKS = 120;
    /** Ticks submerged before treating it as off-plan. A short dunk (a fall clipping water) floats
     *  back up via the universal liquid buoyancy within this window — only sustained submersion
     *  replans, so we don't spam replans that each start underwater again. */
    private static final int UNDERWATER_GRACE_TICKS = 20;
    private int ticksUnderwater = 0;

    public PlayerPathExecutor(NumenPlayer player, Path path, double speed,
                              java.util.function.Supplier<NavContext> ctxSupplier) {
        this.player = player;
        this.path = path;
        this.speed = speed;
        this.ctxSupplier = ctxSupplier;
        this.digger = new BlockDigger(player);
    }

    public Status tick() {
        if (path.isEmpty() || index >= path.movements.size()) {
            return path.partial ? Status.SEGMENT_DONE : Status.ARRIVED;
        }

        Status reloc = relocalize();
        if (reloc != null) return reloc;
        if (index >= path.movements.size()) {
            return path.partial ? Status.SEGMENT_DONE : Status.ARRIVED;
        }

        // Baritone sprint-to-next: flow straight through a traverse→sprintable-ascend
        // junction (sprint up the stairs) instead of arriving at the traverse first. May
        // advance the index BEFORE we resolve the move to drive.
        trySprintSkip();
        // Baritone overrideFall: a fall that lines up with the corridor below it overshoots
        // forward (sprint off the ledge) and splices those traverses. May advance the index.
        tryFallOverride();
        // A splice/skip can land us at the path end (the overshoot ran to the last move) —
        // re-check arrival before resolving a move, or movements.get(index) would overrun.
        if (index >= path.movements.size()) {
            return path.partial ? Status.SEGMENT_DONE : Status.ARRIVED;
        }

        Movement mv = path.movements.get(index);
        Status progress = trackProgress(mv);
        if (progress != null) return progress;

        // Submerged = off-plan ONLY for a fully DRY move. Any move whose src or dest cell
        // is water is PLANNED to operate in/at water — the water-column PILLAR swims up
        // submerged, a shore-exit ASCEND climbs out of a surface cell (dipping under while
        // it digs/places/jumps at the bank), a FALL into a lake plunges below its landing
        // cell before buoyancy brings it back. Cancelling those for being underwater
        // replans the very move that resolves the submersion, and the replanned path is
        // the SAME move again → an infinite churn (observed live: "underwater off-plan 21
        // ticks" firing 5x/min on the same shore-exit ASCENDs, each replan resetting the
        // dig/place progress, the body pacing in the lake forever). A DRY move keeps the
        // watchdog: being dunked while executing it genuinely is off-plan.
        boolean plannedInWater = BlockHelper.isWater(player.level(), mv.src)
                || BlockHelper.isWater(player.level(), mv.dest);
        if (player.isUnderWater() && !plannedInWater) {
            // Don't replan the instant we touch water — let the per-kind drive + buoyancy try to
            // surface first; only a SUSTAINED dunk is genuinely off-plan. Otherwise a fall that
            // clips water replans every tick, each new path again starting underwater.
            if (++ticksUnderwater > UNDERWATER_GRACE_TICKS && canReplanNow()) {
                return replan("underwater off-plan " + ticksUnderwater + " ticks");
            }
        } else {
            ticksUnderwater = 0;
        }

        // A FALL that has LANDED on dry ground OUTSIDE its valid cells can never finish:
        // arrived() needs feet==dest exactly, but driveFall only steers horizontally (a
        // fall never jumps — correct mid-air, useless once grounded), so a landing in a
        // notch below / beside the planned cell just grinds the body against the face
        // under dest until the movement timeout (~cost+100 ticks) rescues it. Observed
        // live: FALL 50,74,-16 -> 50,72,-15 landing at 50,71,-16 (one below dest, behind
        // the dest-floor block), wall-rubbing t=0..114 within the 2-block soft band so no
        // off-path watchdog fired. A grounded mislanding is exactly the settled state
        // P1-5's safeToCancel(FALL onGround) hardening anticipated — replan NOW from
        // where we actually are. In water buoyancy + the liquid float recover instead.
        if (mv.kind == Movement.Kind.FALL && player.onGround() && !player.isInWater()
                && !mv.validPositions().contains(feet()) && canReplanNow()) {
            return replan("fall landed off-plan");
        }
        // Same grind, traverse flavour: grounded 2+ blocks BELOW the lane (the body
        // dropped off it during a planning gap / segment hand-off), the drive halts and
        // step-up hops forever — a 1-block jump can never climb 2+ (observed live:
        // TRAVERSE -1,140,3 -> -2,140,3 hopping at feet y=138 under the lane until the
        // 104-tick timeout). ±1 stays the normal P3-12 settling transient; deeper on land
        // means the plan is stale — replan from the real position. Liquid keeps the
        // existing float-to-surface behaviour.
        if (mv.kind == Movement.Kind.TRAVERSE && player.onGround()
                && player.level().getBlockState(feet()).getFluidState().isEmpty()
                && feet().getY() <= mv.dest.getY() - 2 && canReplanNow()) {
            return replan("grounded below the lane");
        }

        // Baritone cost re-verification: the world may have changed under a planned
        // path (someone broke/placed a block). Re-cost the current + the next few
        // movements against a fresh snapshot; bail if any became impossible, or if
        // the current one got materially more expensive than planned.
        Status reverify = verifyCosts();
        if (reverify != null) return reverify;

        ticksOnCurrent++;
        // Baritone: cancel a movement that overshoots its estimate by movementTimeoutTicks —
        // but only once the move is in a cancellable state (P1-5). A bridge whose floor isn't
        // laid, or an airborne fall, must not be abandoned to the void; the timeout waits for
        // safeToCancel to open (a genuine dead-end still escapes via its own ungated bail,
        // e.g. scaffold-place FAILED, or the fall/place completing).
        if (ticksOnCurrent > mv.cost + PathSettings.MOVEMENT_TIMEOUT_TICKS && canReplanNow()) {
            return replan("movement timeout");
        }

        // 1) Clear obstructions for this move (one at a time), breaking each
        //    progressively over its real hardness time — Baritone holds the dig,
        //    it doesn't pop the block instantly.
        BlockPos obstruction = nextObstruction(mv);
        if (obstruction != null) {
            digger.dig(obstruction);   // faces + breaks the block (halts the body)
            // Baritone walkWhileBreaking (TRAVERSE only): keep approaching + sprint while
            // the front block breaks — but only if neither break cell (dest feet + head) is
            // something to avoid walking into, and we aren't already pressed against the
            // block (Chebyshev dist >= 0.83).
            if (mv.kind == Movement.Kind.TRAVERSE
                    && !BlockHelper.avoidWalkingInto(player.level(), mv.dest)
                    && !BlockHelper.avoidWalkingInto(player.level(), mv.dest.above())
                    && chebyshevDistTo(mv.dest) >= 0.83) {
                player.zza = 1.0f;
                player.setSprinting(speed >= 1.0);
            }
            // The dig halts locomotion but must NOT sink a floating body — float here too
            // (after dig(): its halt() only zeroes zza/xxa, the jump impulse is additive).
            liquidFloat(mv);
            return Status.RUNNING;
        }

        // 2) Place the scaffold floor under dest, if this move needs one — the live
        //    "edge sneak" maneuver (Baritone bridge feel): hold sneak, edge to the
        //    rim, look at the support face, place. Drives the body forward toward the
        //    placement itself, so the bridge is laid as it leans out, not teleport-popped.
        //    (Baritone MovementAscend presses MOVE_BACK after ticksWithoutPlacement>10 to
        //    un-wedge a body standing in the cell it's trying to place into; we instead let
        //    the movement-timeout replan rescue a stalled placement — a bounded fallback,
        //    not the in-move nudge-back. Revisit only if ascend-place wedging shows up.)
        if (mv.toPlace != null && !placedThisMove) {
            if (placeManeuver == null) {
                placeManeuver = new PlaceManeuver(player, mv.toPlace, this::scaffoldSlot,
                        () -> BlockHelper.canWalkOn(player.level(), mv.toPlace));
            }
            switch (placeManeuver.tick()) {
                case DONE -> {
                    placedThisMove = true;
                    placeManeuver = null;
                }
                case FAILED -> {
                    placeManeuver = null;
                    return replan("scaffold place failed");   // out of blocks / no angle → replan
                }
                case RUNNING -> {
                    // The edge-sneak maneuver holds sneak + creeps — in water that sinks the
                    // body tick by tick; keep it floated at the placement's working height.
                    liquidFloat(mv);
                    return Status.RUNNING;
                }
            }
        }

        // 3) Drive toward dest by movement kind — per-Movement updateState,
        //    mirroring Baritone's input-forcing timing.
        if (arrived(mv)) {
            advance();
            return Status.RUNNING;
        }
        drive(mv);
        liquidFloat(mv);
        return Status.RUNNING;
    }

    /**
     * Baritone Movement.update universal liquid float — framework-level, run on EVERY
     * movement tick INCLUDING the break/place phases (Baritone applies it before the
     * per-move updateState): feet cell in liquid and body below {@code dest.y+0.6} →
     * press jump (→ jumpInLiquid buoyancy, +0.04/tick). This is the single mechanism
     * that keeps the body riding the water surface across ALL kinds. Our port used to
     * run it only on the drive tick: the dig/place early-returns skipped it, so a
     * shore-exit ASCEND digging or edge-sneak-placing from a water cell sank tick by
     * tick to the lake floor (dig() halts, PlaceManeuver sneaks — neither floats), the
     * eyes went under, and the underwater watchdog cancelled + replanned the same move
     * forever — the observed water-pacing loop. Self-limiting: only fires while BELOW
     * dest.y+0.6, so downward moves (DESCEND/FALL/DIG_DOWN) never fight their descent.
     */
    private void liquidFloat(Movement mv) {
        if (!player.level().getBlockState(feet()).getFluidState().isEmpty()
                && player.getY() < mv.dest.getY() + 0.6) {
            InputDriver.jump(player);
        }
    }

    /** Horizontal speed² this tick — used to gate jump timing (Baritone waits for motion). */
    private double horizontalSpeedSqr() {
        var v = player.getDeltaMovement();
        return v.x * v.x + v.z * v.z;
    }

    /** Horizontal distance from the body to a cell's centre. */
    private double horizontalDistTo(BlockPos cell) {
        double dx = (cell.getX() + 0.5) - player.getX();
        double dz = (cell.getZ() + 0.5) - player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Chebyshev (max-axis) horizontal distance to a cell centre — Baritone's
     *  walk-while-break "pressed against the block" gate uses this, not Euclidean. */
    private double chebyshevDistTo(BlockPos cell) {
        return Math.max(Math.abs((cell.getX() + 0.5) - player.getX()),
                        Math.abs((cell.getZ() + 0.5) - player.getZ()));
    }

    /** Baritone IPlayerContext.playerFeet: the feet cell, nudged up 0.1251 (so soul-sand /
     *  farmland sink doesn't read us a block low), and — when that cell is a SLAB — taken
     *  as the cell ABOVE it. That slab adjustment is what lets standing on a bottom slab
     *  read as the move's dest (moves onto a slab target the cell above the slab). */
    private BlockPos feet() {
        return BlockHelper.playerFeet(player.level(), player.getX(), player.getY(), player.getZ());
    }

    /**
     * Per-movement execution. Each kind forces the inputs Baritone's
     * {@code updateState} would: walk/diagonal aim + forward (+sprint); ascend
     * waits for forward motion before jumping; parkour holds the jump until clear
     * of the takeoff block; pillar sneaks, jumps and places underfoot at the apex;
     * descend/fall let gravity do the work; dig-down lets phase 1 break the floor.
     */
    private void drive(Movement mv) {
        player.setShiftKeyDown(false);   // default; pillar re-enables per tick
        openDoorsForMove(mv);            // a shut wooden door/gate ahead → open it, don't break it
        Vec3 dest = Vec3.atBottomCenterOf(mv.dest);
        // Baritone never sprints in water, nor when about to run into a hazard just
        // past the destination (it could carry us into lava/cactus/etc). Owner fix
        // order (2026-07-19): also ease OFF the sprint into the path end and into
        // sharp corners — see easeOffSprint.
        boolean sprintBase = speed >= 1.0 && !player.isInWater() && !hazardJustPast(mv)
                && !easeOffSprint(mv);
        switch (mv.kind) {
            case TRAVERSE -> {
                // Baritone MovementTraverse corrects DEPTH before advancing. But a SINGLE-block
                // Y offset on LAND is the normal transient of a step-up / hop settling (or the
                // last block dropping onto the lane) — hard-halting on it every such tick is the
                // P3-12 micro-hitch, and it stutters the P0-1 segment hand-off (whose first move
                // is often a traverse the body enters mid-stride). So: exactly on the lane, or
                // within ±1 on land, KEEP advancing (auto-step + gravity close the 1-block gap as
                // we move, plus a step-up hop when a block low). Only a DEEPER offset, or bobbing
                // in liquid, halts to correct depth first — that is what keeps a water-surface
                // crossing stable instead of porpoising.
                int feetY = feet().getY();
                int dy = feetY - mv.dest.getY();
                boolean feetInLiquid =
                        !player.level().getBlockState(feet()).getFluidState().isEmpty();
                if (dy == 0 || (!feetInLiquid && Math.abs(dy) <= 1)) {
                    // On (or within a settling block of) the lane: advance. Don't sprint across a
                    // floor we just placed (Baritone wasTheBridgeBlockAlwaysThere).
                    InputDriver.stepToward(player, dest, sprintBase && mv.toPlace == null);
                    // A block low on land → add the step-up hop so we climb onto the lane.
                    if (dy < 0 && !feetInLiquid) {
                        InputDriver.jump(player);
                    }
                } else {
                    InputDriver.halt(player);
                    // Below the lane → rise. On LAND that's a step-up hop; in LIQUID the universal
                    // liquid-float jump (in tick()) already does the rising, so don't add a second
                    // impulse here (our jump() isn't an idempotent flag).
                    if (dy < 0 && !feetInLiquid) {
                        InputDriver.jump(player);
                    }
                }
            }
            // Only sprint a diagonal when both cut corners are clear (Baritone sprint() corner check).
            case DIAGONAL -> InputDriver.stepToward(player, dest, sprintBase && diagonalCornersClear(mv));
            case ASCEND -> driveAscend(mv);
            case PARKOUR -> {
                int gap = Math.max(Math.abs(mv.dest.getX() - mv.src.getX()),
                        Math.abs(mv.dest.getZ() - mv.src.getZ()));
                InputDriver.stepToward(player, dest, gap >= 4);   // a 4-gap needs sprint physics
                // Hold the jump until clear of the takeoff block (Baritone dist==3 rule),
                // so the arc starts from the edge, not the centre.
                if (player.onGround() && horizontalDistTo(mv.src) > 0.7) {
                    InputDriver.jump(player);
                }
            }
            case PILLAR -> drivePillar(mv);
            case DESCEND -> driveDescend(mv);
            case FALL -> driveFall(mv);
            case DIG_DOWN -> {
                // Phase 1 breaks the floor; here we just keep centred over the column so
                // gravity drops us straight onto the dug cell (Baritone MovementDownward
                // recentres / moveTowards the break cell rather than free-drifting).
                if (horizontalDistTo(mv.dest) > 0.2) {
                    InputDriver.stepToward(player, Vec3.atBottomCenterOf(mv.dest), false);
                } else {
                    InputDriver.halt(player);
                }
            }
        }
    }

    /**
     * Step down one block (Baritone MovementDescend.updateState): aim one block past
     * dest (fakeDest) for the first ~20 ticks WHILE still near the start
     * (fromStart&lt;1.25), to carry momentum off the ledge; then aim at dest. Stop
     * pushing once horizontally on dest so gravity settles us cleanly.
     */
    private void driveDescend(Movement mv) {
        if (descendSafeMode(mv)) {
            // Baritone safeMode: a slowed, straight-in approach to a 0.17/0.83 weighted
            // point with NO fakeDest momentum — avoids overshooting into a wall / hazard
            // and the skip-to-ascend glitch.
            double dx = (mv.src.getX() + 0.5) * 0.17 + (mv.dest.getX() + 0.5) * 0.83;
            double dz = (mv.src.getZ() + 0.5) * 0.17 + (mv.dest.getZ() + 0.5) * 0.83;
            InputDriver.stepToward(player, new Vec3(dx, mv.dest.getY(), dz), false);
            return;
        }
        double ab = horizontalDistTo(mv.dest);
        if (!feet().equals(mv.dest) || ab > 0.25) {
            // Baritone numTicks++ < 20: post-increment inside the drive branch (counts only
            // ticks we actually drive), gating the fakeDest momentum carry.
            boolean earlyWindow = descendDriveTicks++ < 20 && horizontalDistTo(mv.src) < 1.25;
            // Hold the forward (fakeDest) aim through the airborne phase to avoid the jarring ~180°
            // yaw snap (we hard-set yaw; Baritone rotates gently) — but ONLY on a controlled,
            // non-sprint descend, where overshoot is ≤1 cell (dest/fakeDest, both count as arrived).
            // A sprint-carried descend (a stair chain, 2+ in a row) keeps Baritone's EXACT aim —
            // switch to dest once past the early window — so its momentum is steered back onto the
            // planned line instead of drifting off it.
            boolean commitForward = (!player.onGround() && !sprintDescend) || earlyWindow;
            Vec3 aim = commitForward
                    ? Vec3.atBottomCenterOf(new BlockPos(
                            2 * mv.dest.getX() - mv.src.getX(),
                            mv.dest.getY(),
                            2 * mv.dest.getZ() - mv.src.getZ()))
                    : Vec3.atBottomCenterOf(mv.dest);
            InputDriver.stepToward(player, aim, sprintDescend);
        } else {
            InputDriver.halt(player);
        }
    }

    /** Walk-in distance from the FINAL destination inside which sprint is released
     *  (a real player eases off the sprint key before stopping). */
    private static final double PATH_END_WALK_IN_DIST = 2.2;
    /** Within the last block of a movement whose successor turns sharply, enter the
     *  corner at walk speed instead of sprint-carrying through it. */
    private static final double CORNER_WALK_IN_DIST = 1.1;

    /**
     * Owner fix order (2026-07-19), "走路晕晕乎乎/走超" — sprint never eased off, so
     * the momentum-preserving hand-offs of the fix wave let the body sprint-carry
     * PAST the path end and slam corners. Two walk-in gates on {@code sprintBase}:
     * <ol>
     *   <li><b>path-end ease-off</b> — on the LAST movement, within
     *       {@value #PATH_END_WALK_IN_DIST} blocks of the final dest, walk it in
     *       (arrival tolerances stop reading as overshoot);</li>
     *   <li><b>sharp-corner ease-off</b> — within the last block of a movement whose
     *       successor turns ≥90° (horizontal direction dot ≤ 0), drop sprint so the
     *       corner is entered at walk speed. Straight-throughs and gentle 45° bends
     *       (dot &gt; 0) are untouched.</li>
     * </ol>
     * Deliberately NOT applied to: water (never sprints anyway), the DESCEND/FALL
     * fakeDest momentum window (intentional, its overshoot is ≤1 cell and recovered),
     * PARKOUR's gap≥4 physics sprint (the jump needs it), and the sprint-skip
     * straight-through logic (unchanged). Flight never passes through here — the
     * {@code FlyPathExecutor} has its own approach deceleration.
     */
    private boolean easeOffSprint(Movement mv) {
        if (index >= path.movements.size() - 1) {
            return horizontalDistTo(mv.dest) < PATH_END_WALK_IN_DIST;
        }
        if (horizontalDistTo(mv.dest) >= CORNER_WALK_IN_DIST) {
            return false;
        }
        Movement next = path.movements.get(index + 1);
        int cx = mv.dest.getX() - mv.src.getX();
        int cz = mv.dest.getZ() - mv.src.getZ();
        int nx = next.dest.getX() - next.src.getX();
        int nz = next.dest.getZ() - next.src.getZ();
        if ((cx == 0 && cz == 0) || (nx == 0 && nz == 0)) {
            return false;   // a vertical link (pillar/dig-down) — no horizontal corner to ease
        }
        return cx * nx + cz * nz <= 0;
    }

    /** Baritone MovementDescend.safeMode: a hazard just past dest (sprint-overshoot risk)
     *  OR the skip-to-ascend overshoot-glitch geometry (a wall at foot level with air above). */
    private boolean descendSafeMode(Movement mv) {
        if (hazardJustPast(mv)) return true;
        int dx = mv.dest.getX() - mv.src.getX();
        int dz = mv.dest.getZ() - mv.src.getZ();
        if (dx == 0 && dz == 0) return false;
        BlockPos into = mv.dest.offset(dx, 0, dz);
        return !BlockHelper.canWalkThrough(player.level(), into)
                && BlockHelper.canWalkThrough(player.level(), into.above())
                && BlockHelper.canWalkThrough(player.level(), into.above(2));
    }

    /** Something to avoid in the cell(s) one step PAST the destination — the block we'd
     *  carry into if we over-committed. Baritone's descend safeMode + sprint-suppression
     *  use {@code avoidWalkingInto} (any fluid + the hazard block set) for exactly this. */
    private boolean hazardJustPast(Movement mv) {
        int dx = Integer.signum(mv.dest.getX() - mv.src.getX());
        int dz = Integer.signum(mv.dest.getZ() - mv.src.getZ());
        if (dx == 0 && dz == 0) return false;
        BlockPos into = mv.dest.offset(dx, 0, dz);
        for (int y = 0; y <= 2; y++) {
            if (BlockHelper.avoidWalkingInto(player.level(), into.above(y))) return true;
        }
        return false;
    }

    /** Both diagonal cut-corners clear (Baritone MovementDiagonal.sprint corner check). */
    private boolean diagonalCornersClear(Movement mv) {
        BlockPos cornerA = new BlockPos(mv.src.getX(), mv.src.getY(), mv.dest.getZ());
        BlockPos cornerB = new BlockPos(mv.dest.getX(), mv.src.getY(), mv.src.getZ());
        return BlockHelper.canWalkThrough(player.level(), cornerA)
                && BlockHelper.canWalkThrough(player.level(), cornerA.above())
                && BlockHelper.canWalkThrough(player.level(), cornerB)
                && BlockHelper.canWalkThrough(player.level(), cornerB.above());
    }

    /**
     * Multi-block fall (Baritone MovementFall.updateState, minus the water-bucket
     * MLG which needs a bucket in hand): unlike a descend it does NOT carry momentum
     * — it RECENTRES on the landing cell so it doesn't clip a wall, sneaking while
     * fast-falling ({@code |Δy|>0.4}) to kill horizontal drift and land cleanly.
     */
    private void driveFall(Movement mv) {
        if (fallOverrideAim != null) {
            // Overshoot: sprint forward at the extended aim so we clear the ledge and ride the
            // fall over the corridor below, instead of braking straight down (Baritone overrideFall).
            player.setShiftKeyDown(false);
            InputDriver.lookAt(player, fallOverrideAim);
            player.zza = 1.0f;
            player.xxa = 0.0f;
            player.setSprinting(true);
            return;
        }
        var v = player.getDeltaMovement();
        double cx = mv.dest.getX() + 0.5;
        double cz = mv.dest.getZ() + 0.5;
        // Look ahead by one tick of velocity, like Baritone, to anticipate the drift.
        boolean offCentre = Math.abs(player.getX() + v.x - cx) > 0.1
                || Math.abs(player.getZ() + v.z - cz) > 0.1;
        if (offCentre) {
            // Sneak while fast-falling to brake the horizontal drift toward centre.
            player.setShiftKeyDown(!player.onGround() && Math.abs(v.y) > 0.4);
            InputDriver.lookAt(player, fallAim(mv));   // landing cell, biased off an adjacent ladder
            player.zza = 1.0f;
            player.xxa = 0.0f;
            player.setSprinting(false);
        } else {
            player.setShiftKeyDown(false);
            InputDriver.halt(player);
        }
    }

    /** Aim at the landing cell, nudged 0.125 away from an adjacent ladder/vine so the
     *  fall doesn't grab it mid-drop (Baritone MovementFall.avoid). */
    private Vec3 fallAim(Movement mv) {
        Vec3 c = Vec3.atCenterOf(mv.dest);
        for (Direction d : new Direction[]{
                Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            var s = player.level().getBlockState(mv.dest.relative(d));
            if (s.is(net.minecraft.world.level.block.Blocks.LADDER)
                    || s.is(net.minecraft.world.level.block.Blocks.VINE)) {
                return c.add(-d.getStepX() * 0.125, 0.0, -d.getStepZ() * 0.125);
            }
        }
        return c;
    }

    /** Carry/aim of an overshooting fall. */
    private record FallOverride(Vec3 aim, BlockPos fallDest, int spliceIndex) {}

    /** Baritone PathExecutor overrideFall handling: if the current FALL lines up with the
     *  corridor below it, either splice past it (once we've landed at the extended dest) or
     *  set the forward aim so driveFall sprints off the ledge. Recomputed each tick. */
    private void tryFallOverride() {
        fallOverrideAim = null;
        if (index >= path.movements.size()) return;
        Movement cur = path.movements.get(index);
        if (cur.kind != Movement.Kind.FALL) return;
        FallOverride fo = overrideFall(cur);
        if (fo == null) return;
        if (feet().equals(fo.fallDest)) {
            jumpToIndex(fo.spliceIndex);   // landed at the overshoot dest — continue past the spliced traverses
            return;
        }
        fallOverrideAim = fo.aim;
    }

    /** Baritone PathExecutor.overrideFall: a fall of ≤3 that isn't breaking and is followed by
     *  up to two same-flat-direction traverses, each with a clear column up to the fall's head
     *  and a solid floor, can overshoot forward — the body rides the fall out over the corridor
     *  and lands at the last such traverse's dest. Returns null when no extension is valid. */
    private FallOverride overrideFall(Movement mv) {
        BlockPos dir = dirOf(mv);
        if (dir.getY() < -3) return null;          // too deep to overshoot safely
        if (!mv.toBreak.isEmpty()) return null;    // it's breaking
        BlockPos flatDir = new BlockPos(dir.getX(), 0, dir.getZ());
        int i;
        // Baritone bound is i < path.length()-1; length()==movements+1, so == movements.size().
        for (i = index + 1; i < path.movements.size() && i < index + 3; i++) {
            Movement next = path.movements.get(i);
            if (next.kind != Movement.Kind.TRAVERSE) break;
            if (!flatDir.equals(dirOf(next))) break;
            boolean columnBlocked = false;
            for (int y = next.dest.getY(); y <= mv.src.getY() + 1; y++) {
                BlockPos chk = new BlockPos(next.dest.getX(), y, next.dest.getZ());
                if (!BlockHelper.canWalkThrough(player.level(), chk)) { columnBlocked = true; break; }
            }
            if (columnBlocked) break;
            if (!BlockHelper.canWalkOn(player.level(), next.dest.below())) break;
        }
        i--;
        if (i == index) return null;   // no valid extension exists
        double len = i - index - 0.4;
        int steps = i - index;
        Vec3 aim = new Vec3(flatDir.getX() * len + mv.dest.getX() + 0.5,
                mv.dest.getY(),
                flatDir.getZ() * len + mv.dest.getZ() + 0.5);
        BlockPos fallDest = mv.dest.offset(flatDir.getX() * steps, 0, flatDir.getZ() * steps);
        return new FallOverride(aim, fallDest, i + 1);
    }

    /**
     * Step up one block (Baritone MovementAscend.updateState). Drive forward toward
     * dest, then JUMP when the body is ALIGNED to the move axis (small lateral
     * drift) and either the head is clear or we're close enough — critically NOT
     * gated on forward speed, so hitting the step (which zeroes forward speed) still
     * triggers the jump instead of stalling against the wall.
     */
    private void driveAscend(Movement mv) {
        // Sprint up only when we sprint-skipped into this ascend (Baritone sprintableAscend);
        // a normal ascend jumps from a standstill.
        InputDriver.stepToward(player, Vec3.atBottomCenterOf(mv.dest), sprintAscend);
        // Baritone: stepping onto a bottom slab from a non-slab is a 0.5 walk-up, NOT a jump
        // — auto-step handles the half block, so don't press jump here.
        if (BlockHelper.isBottomSlab(player.level(), mv.dest.below())
                && !BlockHelper.isBottomSlab(player.level(), mv.src.below())) {
            return;
        }
        if (feet().equals(mv.src.above())) {
            return;   // already airborne off the step
        }
        int xAxis = Math.abs(mv.src.getX() - mv.dest.getX()); // 0 or 1
        int zAxis = Math.abs(mv.src.getZ() - mv.dest.getZ()); // 0 or 1
        double px = player.getX();
        double pz = player.getZ();
        double flatDistToNext = xAxis * Math.abs((mv.dest.getX() + 0.5) - px)
                + zAxis * Math.abs((mv.dest.getZ() + 0.5) - pz);
        double sideDist = zAxis * Math.abs((mv.dest.getX() + 0.5) - px)
                + xAxis * Math.abs((mv.dest.getZ() + 0.5) - pz);
        var dm = player.getDeltaMovement();
        double lateralMotion = xAxis * dm.z + zAxis * dm.x;   // drift perpendicular to the move axis
        if (Math.abs(lateralMotion) > 0.1) {
            return;   // still drifting sideways — wait until aligned
        }
        if (headBonkClear(mv.src)) {
            InputDriver.jump(player);   // head's clear above — jump now (InputDriver gates onGround)
            return;
        }
        if (flatDistToNext > 1.2 || sideDist > 0.2) {
            return;   // too far / off-axis — would bonk an adjacent block
        }
        InputDriver.jump(player);
    }

    /** Baritone headBonkClear: the four horizontals above src+2 are passable, so a
     *  jump won't smack the body's head into a ceiling block. */
    private boolean headBonkClear(BlockPos src) {
        BlockPos up2 = src.above(2);
        for (Direction d : new Direction[]{
                Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            if (!BlockHelper.canWalkThrough(player.level(), up2.relative(d))) {
                return false;
            }
        }
        return true;
    }

    /** Baritone PathExecutor.shouldSprintNextTick (traverse→ascend case): if the current
     *  traverse leads straight into a sprintable ascend and we're centred enough to commit
     *  (skipNow), skip the traverse's arrival and sprint up the step. Advances the index. */
    private boolean trySprintSkip() {
        // Descend-sprint is re-decided every tick (Baritone re-forces SPRINT per tick), so
        // clear it up front; the descend branch below re-asserts it when still applicable.
        sprintDescend = false;
        if (index >= path.movements.size() - 1) return false;   // need at least cur + next
        Movement cur = path.movements.get(index);
        Movement next = path.movements.get(index + 1);
        // Baritone decides the skip AFTER the current move's own update() has handled its
        // break that tick; we run before the phases, so don't skip past a move whose own
        // obstruction is still pending (it would cancel that dig).
        if (nextObstruction(cur) != null) return false;
        // Traverse → sprintable ascend: skip the traverse's arrival and sprint up the step.
        if (cur.kind == Movement.Kind.TRAVERSE && next.kind == Movement.Kind.ASCEND
                && index < path.movements.size() - 2
                && sprintableAscend(cur, next, path.movements.get(index + 2)) && skipNow(cur)) {
            advance();              // skip straight into the ascend
            sprintAscend = true;    // (advance() reset it; set after)
            return true;
        }
        // Descend → sprintable continuation (Baritone canSprintFromDescendInto): carry sprint
        // down the descend into a same-direction descend chain or a traverse/diagonal, and
        // hand off the moment we reach the descend's landing cell.
        //   safeMode gate: Baritone is `safeMode() && !skipToAscend()` (it still sprints to
        //   clip through the skip-to-ascend glitch); we gate on plain `!descendSafeMode` —
        //   intentionally NOT sprinting into that clip-glitch, consistent with our stricter-
        //   safety stance. Same conservative direction the rest of the port takes.
        if (cur.kind == Movement.Kind.DESCEND && !descendSafeMode(cur)
                && canSprintFromDescendInto(cur, next)) {
            // Baritone next_next veto: if this descend feeds a descend chain whose THIRD link
            // can't itself be sprinted into, don't build momentum we can't safely shed.
            if (next.kind == Movement.Kind.DESCEND && index + 2 < path.movements.size()) {
                Movement nextNext = path.movements.get(index + 2);
                if (nextNext.kind == Movement.Kind.DESCEND
                        && !canSprintFromDescendInto(next, nextNext)) {
                    return false;
                }
            }
            if (feet().equals(cur.dest)) {
                advance();
                return trySprintSkip();   // re-decide sprint for the NEW current (Baritone onTick recursion)
            }
            sprintDescend = true;   // sprint this descend this tick
            return true;
        }
        return false;
    }

    /** Baritone PathExecutor.canSprintFromDescendInto: a descend keeps sprint into the next
     *  move only when it's a same-direction descend chain, or — with a solid floor one block
     *  below-and-forward of the landing — a diagonal (allowOvershootDiagonalDescend, on by
     *  default). NB Baritone's traverse branch compares next.getDirection() (y=0) to the
     *  descend's getDirection() (y=-1) and so can NEVER match: a descend never sprint-flows
     *  into a traverse. We mirror that real output (the body settles at the landing, then the
     *  traverse drives itself), not the branch's apparent intent. */
    private boolean canSprintFromDescendInto(Movement cur, Movement next) {
        if (next.kind == Movement.Kind.DESCEND && dirOf(cur).equals(dirOf(next))) {
            return true;
        }
        // dirOf(cur) is (dx,-1,dz), so dest.offset(dir) is one block below-AND-forward.
        if (!BlockHelper.canWalkOn(player.level(), cur.dest.offset(dirOf(cur)))) return false;
        return next.kind == Movement.Kind.DIAGONAL;
    }

    /** Baritone PathExecutor.sprintableAscend (default sprintAscends=true): the traverse and
     *  the following ascend share a horizontal direction (as does the move after), both have
     *  a solid floor, the ascend breaks nothing, the 2x3 column over the source is clear, and
     *  neither the block above the head nor above the ascend's head is something to avoid. */
    private boolean sprintableAscend(Movement cur, Movement next, Movement nextnext) {
        net.minecraft.world.level.Level level = player.level();
        BlockPos curDir = dirOf(cur);     // (dx,0,dz)
        BlockPos nextDir = dirOf(next);   // (dx,1,dz)
        if (curDir.getX() != nextDir.getX() || curDir.getZ() != nextDir.getZ()) return false;
        BlockPos nnDir = dirOf(nextnext);
        if (nnDir.getX() != nextDir.getX() || nnDir.getZ() != nextDir.getZ()) return false;
        if (!BlockHelper.canWalkOn(level, cur.dest.below())) return false;
        if (!BlockHelper.canWalkOn(level, next.dest.below())) return false;
        if (!next.toBreak.isEmpty()) return false;   // it's breaking
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 3; y++) {
                BlockPos chk = cur.src.above(y);
                if (x == 1) chk = chk.offset(curDir.getX(), 0, curDir.getZ());
                if (!BlockHelper.canWalkThrough(level, chk)) return false;
            }
        }
        if (BlockHelper.avoidWalkingInto(level, cur.src.above(3))) return false;
        return !BlockHelper.avoidWalkingInto(level, next.dest.above(2));
    }

    /** Baritone PathExecutor.skipNow: centred on the move axis (off-axis < 0.1) AND either
     *  the cell we'd head-bonk on is clear, or we've travelled far enough (flat dist > 0.8). */
    private boolean skipNow(Movement cur) {
        BlockPos dir = dirOf(cur);
        double offTarget = Math.abs(dir.getX() * (cur.src.getZ() + 0.5 - player.getZ()))
                + Math.abs(dir.getZ() * (cur.src.getX() + 0.5 - player.getX()));
        if (offTarget > 0.1) return false;
        BlockPos headBonk = cur.src.offset(-dir.getX(), 0, -dir.getZ()).above(2);
        if (BlockHelper.canWalkThrough(player.level(), headBonk)) return true;
        double flatDist = Math.abs(dir.getX() * (headBonk.getX() + 0.5 - player.getX()))
                + Math.abs(dir.getZ() * (headBonk.getZ() + 0.5 - player.getZ()));
        return flatDist > 0.8;
    }

    /** The net move vector (dest − src) — Baritone Movement.getDirection. */
    private static BlockPos dirOf(Movement mv) {
        return mv.dest.subtract(mv.src);
    }

    /**
     * Block-tower pillar (Baritone MovementPillar): sneak so we never step off the
     * column, stay centred, jump from the ground, and at the apex place a block in
     * the cell we just left so we land one higher.
     */
    private void drivePillar(Movement mv) {
        // Swim straight up a water column (Baritone MovementPillar water branch, verbatim):
        // aim at the destination cell's centre and press forward ONLY when off-centre by
        // >0.2 (just to recentre) — buoyancy does the actual rising. No sneak, no place.
        if (BlockHelper.isWater(player.level(), mv.src)) {
            player.setShiftKeyDown(false);
            InputDriver.lookAt(player, Vec3.atCenterOf(mv.dest));
            double cx = mv.dest.getX() + 0.5;
            double cz = mv.dest.getZ() + 0.5;
            player.zza = (Math.abs(player.getX() - cx) > 0.2 || Math.abs(player.getZ() - cz) > 0.2)
                    ? 1.0f : 0.0f;
            player.xxa = 0.0f;
            player.setSprinting(false);
            // The rise itself comes from the universal liquid-float jump in tick() — Baritone's
            // pillar water branch likewise presses no jump here, it relies on Movement.update.
            return;
        }
        player.setShiftKeyDown(true);   // sneak: never step off the column
        // Recentre on the column rather than walking off it.
        if (horizontalDistTo(mv.src) > 0.17) {
            InputDriver.stepToward(player, Vec3.atBottomCenterOf(mv.src), false);
        } else {
            InputDriver.halt(player);
            // Baritone MovementPillar: jump only when nearly still AND still BELOW the
            // destination — stop jumping once we've reached the top (`y < dest.y`).
            if (player.onGround() && horizontalSpeedSqr() < 0.0025
                    && player.getY() < mv.dest.getY()) {
                InputDriver.jump(player);
            }
        }
        // Once risen above the target foot height (the apex), place the block under
        // our feet against the block below — looking down at it, like a real player
        // (Baritone: CLICK_RIGHT when crouching + looking at the block below + y > dest.y+0.1).
        if (player.getY() > mv.dest.getY() + 0.1) {
            placeUnderfoot(mv.src);
        }
    }

    /** Place a scaffold block at {@code cell} against the solid block directly below it
     *  — the pillar apex place, performed natively (look down + useItemOn on the up-face). */
    private void placeUnderfoot(BlockPos cell) {
        int slot = scaffoldSlot();
        if (slot < 0) return;
        if (!Placement.canPlaceAgainst(player.level(), cell.below())) return;
        InputDriver.lookAt(player, Vec3.atBottomCenterOf(cell));   // look straight down at the support's top
        // Baritone crouch-confirm: only place once actually crouching — the sneak takes
        // effect the tick AFTER we request it, and isCrouching() is that "took effect" signal.
        if (!player.isCrouching()) return;
        BlockHitResult hit = Placement.resolve(player, cell, true);  // honest raycast only — no fabricated hit
        if (hit == null) return;
        player.holdInHand(slot);   // real hotbar-select / swap-to-hand, not an aliasing overwrite
        Interaction.useBlock(player, hit, InteractionHand.MAIN_HAND).tick();
    }

    /**
     * Toggle any wooden door / fence gate that blocks THIS movement (the dest cell + the head
     * cell above it, each judged from the cell we approach it from) — Baritone's MovementTraverse
     * door/gate handling, the alternative to breaking it. The planner already treats openable
     * doors as passable (so they never enter {@code toBreak}); here we right-click via the shared
     * {@link Interaction#useBlock} primitive, which does a REAL eye-raycast (no fabricated hit) and
     * only fires when the door is actually in line of sight — exactly like a player, and the same
     * interaction path mining uses. Not sneaking (drive() cleared shift), so the door's own use()
     * fires (open/close) rather than placing a held block against it.
     */
    private void openDoorsForMove(Movement mv) {
        toggleDoorwayIfBlocking(mv.dest, mv.src);
        toggleDoorwayIfBlocking(mv.dest.above(), mv.src.above());
    }

    private void toggleDoorwayIfBlocking(BlockPos cell, BlockPos from) {
        if (!BlockHelper.isOpenableDoor(player.level().getBlockState(cell))) {
            return;   // not a wooden door / fence gate (iron doors stay a solid obstruction)
        }
        if (BlockHelper.isDoorwayPassable(player.level(), cell, from)) {
            return;   // already passable for this approach — nothing to toggle
        }
        // Real line-of-sight right-click (raycast-verified); a blocked sightline just retries
        // next tick as the body lines up, matching Baritone's "no reachable rotation → no click".
        Interaction.useBlock(player, cell, InteractionHand.MAIN_HAND).tick();
    }

    /**
     * Baritone PathExecutor cost re-verification. The current movement is re-costed
     * every tick (catches a block change directly in front of us); the lookahead
     * window is re-verified only when we just advanced to a new movement (Baritone's
     * {@code costEstimateIndex} guard). Cancels — triggering a replan — if any
     * movement became impossible ({@code COST_INF}) or the current one's live cost
     * rose by more than {@code maxCostIncrease} over its planned estimate.
     */
    private Status verifyCosts() {
        NavContext fresh = ctxSupplier.get();
        Movement cur = path.movements.get(index);
        // Baritone gates EVERY cost-based cancel on canCancel = movement.safeToCancel():
        // a movement mid-commit (airborne, mid-jump, mid-place, bridging over air) must
        // not be abandoned to a replan, or the body is dropped into a bad state. While
        // not cancellable, let the move finish; the movement timeout still bounds a stall.
        if (!safeToCancel(cur)) {
            return null;
        }
        // Movements whose OWN execution mutates the world — pillar places a block
        // underfoot, dig-down breaks the floor — would self-trigger COST_INF the
        // instant they act (regeneration no longer emits them). Skip live re-costing
        // for those; the movement timeout still guards a genuine stall. Movements
        // that only consume the world (break an obstruction) get cheaper, never
        // INF, so they're safe to verify.
        if (!selfMutating(cur.kind)) {
            double liveCur = recost(fresh, cur);
            if (liveCur >= ActionCosts.COST_INF) {
                return replan("current move now impossible (" + obstruct(fresh, cur) + ")");
            }
            if (liveCur - cur.cost > PathSettings.MAX_COST_INCREASE) {
                return replan("current move got too expensive");
            }
        }
        if (costCheckIndex != index) {
            costCheckIndex = index;
            int last = path.movements.size() - 1;
            // Baritone: `i = 1; i < costVerificationLookahead(5)` → 4 movements ahead
            // (index+1 .. index+4), bounded by `pathPosition+i < length-1` (== <= last).
            for (int i = index + 1; i < index + PathSettings.COST_VERIFICATION_LOOKAHEAD && i <= last; i++) {
                Movement ahead = path.movements.get(i);
                if (!selfMutating(ahead.kind)
                        && recost(fresh, ahead) >= ActionCosts.COST_INF) {
                    return replan("lookahead +" + (i - index) + " impossible: " + ahead.kind + " "
                            + ahead.src.toShortString() + "->" + ahead.dest.toShortString()
                            + " (" + obstruct(fresh, ahead) + ")");
                }
            }
        }
        return null;
    }

    /**
     * P1-5 replan-safety gate applied to EVERY replan EXIT — relocalize (off-path bands),
     * underwater, movement-timeout, and the stuck backstop — not just the cost re-verification
     * {@link #safeToCancel} originally guarded (that was the sole safe-gated exit; the rest
     * abandoned the move unconditionally). A move that is mid-commit — an airborne fall, a
     * bridge whose floor isn't laid yet — must not be cancelled, or the body is dropped into
     * the void / off the ledge. When this is false the triggering watchdog HOLDS its replan
     * (its counter keeps accruing) until the move reaches a cancellable state; the movement
     * timeout stays the final backstop, it just waits for {@link #safeToCancel} to open. A
     * genuine dead-end that can never become cancellable keeps its own ungated escape (the
     * scaffold-place FAILED → replan, and the FALL/ASCEND "settled once on ground / placed"
     * clauses so a bad landing or post-place stall can still time out).
     */
    private boolean canReplanNow() {
        return index >= path.movements.size()
                || safeToCancel(path.movements.get(index));
    }

    /**
     * Baritone PathExecutor's {@code canCancel = movement.safeToCancel()} gate, mapped
     * to our movement kinds + live body state. A movement that is mid-commit must not
     * be cancelled (replanned) — doing so drops the body off a fall, fails a jump, or
     * strands it mid-bridge. Mirrors each Baritone Movement.safeToCancel override:
     * <ul>
     *   <li>FALL — safe before stepping off the edge (still at {@code src}) or once landed
     *       ({@code onGround}); unsafe only mid-air;</li>
     *   <li>PARKOUR — only on the takeoff (0th) tick, no momentum knowledge after;</li>
     *   <li>ASCEND — unsafe only while actively placing the step block; safe once it's placed;</li>
     *   <li>TRAVERSE — a sneak-bridge over air can't be abandoned until its floor exists;</li>
     *   <li>others (DIAGONAL/DESCEND/PILLAR/DIG_DOWN) — base {@code true}.</li>
     * </ul>
     */
    private boolean safeToCancel(Movement mv) {
        switch (mv.kind) {
            case FALL:
                // Safe before committing off the edge (still at src) OR once we've landed
                // (onGround) — mid-air the drop is physically committed and cancelling would
                // strand the body, but a completed drop is a settled state a replan may cancel
                // from. The onGround clause matters now that P1-5 also gates the OFF-PATH exits:
                // without it a fall that lands off its planned column is "unsafe" forever and
                // could never replan out of a bad landing → wedge.
                return feet().equals(mv.src) || player.onGround();
            case PARKOUR:
                // P1-5 × parkour-enable reconciliation (integration glue). The bare
                // `ticksOnCurrent==0` was written while ALLOW_PARKOUR was off, so the
                // PARKOUR branch was dead and never had to survive the P1-5 gate. Now
                // the planner emits 2-cell gap jumps: a MISSED hop that drops into the
                // pit stays on this move with ticksOnCurrent>0, so this gate would be
                // false forever — and since P1-5 now routes EVERY watchdog replan exit
                // (off-path, movement-timeout, stuck) through canReplanNow()=safeToCancel,
                // none could ever fire and the body wedges in the gap until the task
                // deadline. Relax exactly as FALL is (and as the parkour-enable commit
                // db37447 directs): cancellable pre-liftoff (still running up, onGround)
                // and once landed (onGround), locked only through the airborne arc.
                return ticksOnCurrent == 0 || player.onGround();
            case ASCEND:
                // Unsafe only while ACTIVELY placing the step block (mid-place, floor not yet
                // down) — Baritone gates on ticksWithoutPlacement>0. We additionally treat a
                // FINISHED placement (block now walkable) as settled: the body stands on solid
                // ground at src, so a post-place hop-stall can still time out under the P1-5
                // gate instead of wedging (the middle clause alone stays false forever once
                // placedThisMove flips true).
                return mv.toPlace == null
                        || (placeManeuver == null && !placedThisMove)
                        || BlockHelper.canWalkOn(player.level(), mv.toPlace);
            case TRAVERSE:
                return mv.toPlace == null
                        || BlockHelper.canWalkOn(player.level(), mv.dest.below());
            case DIAGONAL:
                return diagonalSafeToCancel(mv);
            default:
                return true;
        }
    }

    /** Baritone MovementDiagonal.safeToCancel: safe at the start cell, or when both cut
     *  corners have a floor; if we're cornering through an unwalkable corner cell, only
     *  safe when a block actually supports us (one of the four 0.25 offsets below). */
    private boolean diagonalSafeToCancel(Movement mv) {
        net.minecraft.world.level.Level level = player.level();
        BlockPos feet = feet();
        if (feet.equals(mv.src)) return true;
        BlockPos floorA = new BlockPos(mv.src.getX(), mv.src.getY() - 1, mv.dest.getZ());
        BlockPos floorB = new BlockPos(mv.dest.getX(), mv.src.getY() - 1, mv.src.getZ());
        if (BlockHelper.canWalkOn(level, floorA) && BlockHelper.canWalkOn(level, floorB)) {
            return true;
        }
        BlockPos cornerA = new BlockPos(mv.src.getX(), mv.src.getY(), mv.dest.getZ());
        BlockPos cornerB = new BlockPos(mv.dest.getX(), mv.src.getY(), mv.src.getZ());
        if (feet.equals(cornerA) || feet.equals(cornerB)) {
            double off = 0.25;
            double x = player.getX(), y = player.getY() - 1, z = player.getZ();
            return BlockHelper.canWalkOn(level, BlockPos.containing(x + off, y, z + off))
                    || BlockHelper.canWalkOn(level, BlockPos.containing(x + off, y, z - off))
                    || BlockHelper.canWalkOn(level, BlockPos.containing(x - off, y, z + off))
                    || BlockHelper.canWalkOn(level, BlockPos.containing(x - off, y, z - off));
        }
        return true;
    }

    /** Kinds whose execution changes the world such that regenerating them mid-move
     *  would spuriously report them gone (place-under / break-floor). */
    private static boolean selfMutating(Movement.Kind kind) {
        return kind == Movement.Kind.PILLAR
                || kind == Movement.Kind.DIG_DOWN;
    }

    /**
     * Recompute a movement's live cost from a fresh world snapshot: regenerate the
     * moves out of its source cell and match the same kind + destination.
     * {@code COST_INF} if it's no longer producible (the world changed so the move
     * is gone).
     */
    private double recost(NavContext fresh, Movement mv) {
        for (Movement gen : Moves.generate(fresh, mv.src)) {
            if (gen.kind == mv.kind && gen.dest.equals(mv.dest)) {
                return gen.cost;
            }
        }
        return ActionCosts.COST_INF;
    }

    /**
     * Has the move completed? Feet at dest, or — for a walk — overshot 1–2 cells past
     * it at the same height (Baritone {@code overshootTraverse}: a sprint can carry the
     * body a cell or two beyond the target, which still counts as arrived).
     */
    private boolean arrived(Movement mv) {
        BlockPos feet = feet();
        if (mv.kind == Movement.Kind.PILLAR) {
            // Water swim-up: Baritone's pillar water branch succeeds on feet==dest with NO
            // block check (you're swimming, nothing is placed).
            if (BlockHelper.isWater(player.level(), mv.src)) {
                return feet.equals(mv.dest);
            }
            // Dry tower: Baritone success = feet at dest AND blockIsThere (the placed block
            // exists, canWalkOn(src)). WITHOUT the block check, the jump APEX — feet
            // momentarily at dest.y mid-air, before placeUnderfoot has placed anything —
            // false-arrives, advances the index, then the body falls back with nothing
            // placed and churns forever. The block check holds arrival until we've placed.
            return feet.equals(mv.dest) && BlockHelper.canWalkOn(player.level(), mv.src);
        }
        if (mv.kind == Movement.Kind.DESCEND) {
            // Baritone MovementDescend success: feet at dest OR the overshoot fakeDest, AND
            // settled within 0.5 of dest.y (or dest is liquid) — don't advance while still
            // falling through the destination cell.
            BlockPos fakeDest = new BlockPos(
                    2 * mv.dest.getX() - mv.src.getX(), mv.dest.getY(), 2 * mv.dest.getZ() - mv.src.getZ());
            if (!feet.equals(mv.dest) && !feet.equals(fakeDest)) return false;
            return !player.level().getBlockState(mv.dest).getFluidState().isEmpty()
                    || player.getY() - mv.dest.getY() < 0.5;
        }
        if (mv.kind == Movement.Kind.FALL) {
            // Baritone MovementFall success: feet at dest AND settled within 0.094 of dest.y
            // (lilypad tolerance); or landed in water and no longer sinking (no water-bucket
            // clutch at default settings).
            if (!feet.equals(mv.dest)) return false;
            if (!player.level().getBlockState(mv.dest).getFluidState().isEmpty()) {
                return player.getDeltaMovement().y >= 0.0;
            }
            return player.getY() - mv.dest.getY() < 0.094;
        }
        if (mv.kind == Movement.Kind.TRAVERSE) {
            // Baritone: SUCCESS only once the floor under dest exists (isTheBridgeBlockThere
            // = canWalkOn(dest.below)) — don't declare a bridge traverse done before the
            // placed floor is there. Then feet==dest, or a 1-2 cell sprint overshoot in the
            // move direction (Baritone overshootTraverse).
            if (!BlockHelper.canWalkOn(player.level(), mv.dest.below())) return false;
            if (feet.equals(mv.dest)) return true;
            int dx = Integer.signum(mv.dest.getX() - mv.src.getX());
            int dz = Integer.signum(mv.dest.getZ() - mv.src.getZ());
            if (dx != 0 || dz != 0) {
                BlockPos one = mv.dest.offset(dx, 0, dz);
                if (feet.equals(one) || feet.equals(one.offset(dx, 0, dz))) return true;
            }
            return false;
        }
        if (mv.kind == Movement.Kind.ASCEND) {
            // Baritone MovementAscend success: feet==dest OR one cell further horizontally
            // (the jump can carry us a cell past dest).
            if (feet.equals(mv.dest)) return true;
            int dx = mv.dest.getX() - mv.src.getX();
            int dz = mv.dest.getZ() - mv.src.getZ();
            return feet.equals(mv.dest.offset(dx, 0, dz));
        }
        if (mv.kind == Movement.Kind.DIAGONAL) {
            // Baritone declares a diagonal done only at feet==dest, but a SPRINTED diagonal
            // routinely carries the body one cell past dest along the move axis; without an
            // overshoot tolerance it never reads "arrived", faceYaw snaps hard back to dest,
            // and the body bangs back and forth across the boundary (P1-3). Count the one-cell
            // diagonal overshoot as arrived (mirrors the TRAVERSE overshoot tolerance, and the
            // matching cell is anchored in the move's validPositions).
            if (feet.equals(mv.dest)) return true;
            int dx = Integer.signum(mv.dest.getX() - mv.src.getX());
            int dz = Integer.signum(mv.dest.getZ() - mv.src.getZ());
            return feet.equals(mv.dest.offset(dx, 0, dz));
        }
        // DIG_DOWN, PARKOUR: feet==dest only.
        return feet.equals(mv.dest);
    }

    /** The next still-solid block this move must break, or null when its path is clear. */
    private BlockPos nextObstruction(Movement mv) {
        for (BlockPos pos : mv.toBreak) {
            if (!player.level().getBlockState(pos).isAir()) {
                return pos;
            }
        }
        return null;
    }

    /** The inventory slot of a scaffold block (cobble/dirt/…), or -1 if none. */
    private int scaffoldSlot() {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (NavContext.isScaffold(inv.getItem(i))) return i;
        }
        return -1;
    }

    // ---- re-localization (mirrors PathExecutor) ----

    private Status relocalize() {
        BlockPos feet = feet();
        Movement cur = path.movements.get(index);
        if (cur.validPositions().contains(feet)) {
            ticksAway = 0;
            return null;
        }
        // Resync to the movement whose valid cells hold us that is CLOSEST in path order to
        // where we think we are (P2-9). The old rule snapped BACKWARD to the EARLIEST match
        // and only then looked forward — but DESCEND/FALL landing boxes overlap between
        // segments, so "earliest" teleports the index to a far-back segment and it thrashes.
        // Take the least path-order distance instead. Backward matches snap to i (lagged /
        // pushed back); forward matches snap to i-1 (a move self-signals its completion, so
        // +1/+2 are deliberately skipped — Baritone PathExecutor). NB the backward scan runs
        // from index-1 DOWNWARD, so its first hit is the NEAREST earlier move, not the earliest.
        int last = path.movements.size() - 1;
        int backIdx = -1;
        for (int i = index - 1; i >= 0; i--) {
            if (path.movements.get(i).validPositions().contains(feet)) { backIdx = i; break; }
        }
        int fwdIdx = -1;
        for (int i = index + 3; i <= last; i++) {
            if (path.movements.get(i).validPositions().contains(feet)) { fwdIdx = i; break; }
        }
        if (backIdx >= 0 || fwdIdx >= 0) {
            // Compare the RESULTING index's path-order distance (forward resolves to fwdIdx-1);
            // ties favour the backward snap, preserving Baritone's lagged-detection priority.
            int backDist = (backIdx >= 0) ? index - backIdx : Integer.MAX_VALUE;
            int fwdDist = (fwdIdx >= 0) ? (fwdIdx - 1) - index : Integer.MAX_VALUE;
            if (fwdDist < backDist) jumpToIndex(fwdIdx - 1);
            else jumpToIndex(backIdx);
            return null;
        }
        // Off-path watchdog (Baritone PathExecutor.onTick + closestPathPos): distance to the
        // closest valid cell of the WHOLE path — not just the current movement — so the body
        // being near any part of the route doesn't read as off-path. Two bands: >2 blocks
        // counts toward MAX_TICKS_AWAY before giving up, >3 cancels immediately.
        double bestSq = Double.MAX_VALUE;
        for (Movement m : path.movements) {
            for (BlockPos vp : m.validPositions()) {
                double d = player.distanceToSqr(Vec3.atCenterOf(vp));
                if (d < bestSq) bestSq = d;
            }
        }
        // Soft band first (matches Baritone's order), then the immediate hard band. Both are
        // gated by canReplanNow (P1-5): the ticksAway counter keeps accruing while the move is
        // mid-commit (so it fires the instant the move becomes cancellable), but we never
        // abandon an airborne / bridging move to the void just because we drifted off the line
        // — a fall that landed off-path becomes cancellable via safeToCancel(FALL)'s onGround
        // clause the tick it touches down.
        if (possiblyOffPath(cur, bestSq, SOFT_DIST_SQR)) {
            if (++ticksAway > PathSettings.MAX_TICKS_AWAY && canReplanNow()) {
                return replan("off-path soft band " + PathSettings.MAX_TICKS_AWAY + " ticks");
            }
        } else {
            ticksAway = 0;
        }
        if (possiblyOffPath(cur, bestSq, HARD_DIST_SQR) && canReplanNow()) {
            return replan("off-path hard band (>3 blocks)");
        }
        return null;
    }

    /** Baritone PathExecutor.possiblyOffPath: are we further than {@code leniencySq} from the
     *  path? With a mid-FALL carve-out — falling you're far in Y from both ends but not off
     *  path, so judge a fall by the FLAT (XZ) distance to its landing cell instead. */
    private boolean possiblyOffPath(Movement cur, double bestSq, double leniencySq) {
        if (bestSq <= leniencySq) return false;
        if (cur.kind == Movement.Kind.FALL) {
            double dx = (cur.dest.getX() + 0.5) - player.getX();
            double dz = (cur.dest.getZ() + 0.5) - player.getZ();
            return (dx * dx + dz * dz) >= leniencySq;
        }
        return true;
    }

    /** Why a move re-costs to COST_INF, in words — the break-veto on its line, or a note that the
     *  block obstruction is clean (so it failed for support/fall/geometry, e.g. the floor is gone). */
    private String obstruct(NavContext fresh, Movement mv) {
        String why = fresh.diagnoseObstruction(mv.src, mv.dest);
        return why != null ? why : "no break-veto (support/fall/geometry)";
    }

    /** Log a replan trigger with full context (rare event), then return the status. */
    private Status replan(String why) {
        Movement mv = index < path.movements.size() ? path.movements.get(index) : null;
        Constants.LOG.info("[numen-path] REPLAN {} | {}", why, mv != null ? desc(mv) : "feet=" + feet().toShortString());
        return Status.NEEDS_REPLAN;
    }

    /** One-line snapshot of the current move + body state for diagnostics. */
    private String desc(Movement mv) {
        return String.format(
                "%s %s->%s feet=%s y=%.2f grnd=%b sprintDesc=%b t=%d/%.0f away=%d d2path=%.2f idx=%d/%d",
                mv.kind, mv.src.toShortString(), mv.dest.toShortString(), feet().toShortString(),
                player.getY(), player.onGround(), sprintDescend,
                ticksOnCurrent, mv.cost + PathSettings.MOVEMENT_TIMEOUT_TICKS,
                ticksAway, Math.sqrt(distToPathSq()), index, path.movements.size());
    }

    /** Squared distance from the body to the nearest valid cell of the WHOLE path (diagnostics). */
    private double distToPathSq() {
        double best = Double.MAX_VALUE;
        for (Movement m : path.movements) {
            for (BlockPos vp : m.validPositions()) {
                double d = player.distanceToSqr(Vec3.atCenterOf(vp));
                if (d < best) best = d;
            }
        }
        return best;
    }

    /** Track NET forward progress (vs the furthest index reached) so an index that bounces between
     *  snap-back and skip-forward can't mask a stall: warn once at ~3s, and force a replan at ~6s
     *  IF the per-movement timeout is being defeated (low {@code ticksOnCurrent} = the move keeps
     *  restarting). A legit long move has a high {@code ticksOnCurrent} climbing to its own timeout,
     *  so this backstop leaves it alone. Returns a replan status, or null to continue. */
    private Status trackProgress(Movement mv) {
        if (index > maxIndexReached) {
            maxIndexReached = index;
            ticksSinceProgress = 0;
            stuckWarned = false;
            return null;
        }
        ticksSinceProgress++;
        if (ticksSinceProgress >= STUCK_WARN_TICKS && !stuckWarned) {
            stuckWarned = true;
            Constants.LOG.warn("[numen-path] STUCK no forward progress {} ticks | {}",
                    ticksSinceProgress, desc(mv));
        }
        if (ticksSinceProgress >= STUCK_REPLAN_TICKS && ticksOnCurrent < STUCK_WARN_TICKS
                && canReplanNow()) {
            return replan("no net progress " + ticksSinceProgress + " ticks (index thrash)");
        }
        return null;
    }

    private void jumpToIndex(int i) {
        index = i;
        resetMoveState();
    }

    private void advance() {
        index++;
        resetMoveState();
    }

    private void resetMoveState() {
        ticksAway = 0;
        ticksOnCurrent = 0;
        sprintAscend = false;
        sprintDescend = false;
        descendDriveTicks = 0;
        digger.cancel();          // a partial dig belongs to the move we just left
        placedThisMove = false;
        if (placeManeuver != null) {
            placeManeuver.stop();
            placeManeuver = null;
        }
    }

    public boolean isPartial() {
        return path.partial;
    }

    public BlockPos pathEnd() {
        return path.end;
    }

    public int remainingMovements() {
        return Math.max(0, path.movements.size() - index);
    }

    /** Estimated ticks left in this segment — Σ cost of the unplayed movements. */
    public double remainingCost() {
        double c = 0.0;
        for (int i = Math.max(index, 0); i < path.movements.size(); i++) {
            c += path.movements.get(i).cost;
        }
        return c;
    }

    public void stop() {
        digger.cancel();
        if (placeManeuver != null) {
            placeManeuver.stop();
            placeManeuver = null;
        }
        InputDriver.halt(player);
        // Release sneak too — pillar/place hold it every tick; without this it lingers
        // through a mid-path replan's planning ticks (the next path's drive() clears it,
        // but only once it starts). Mirrors PlayerNav.stop().
        player.setShiftKeyDown(false);
    }
}
