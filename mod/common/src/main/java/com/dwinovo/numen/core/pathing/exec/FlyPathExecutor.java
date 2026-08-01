package com.dwinovo.numen.core.pathing.exec;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.core.pathing.calc.FlyPlanner;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.calc.Path;
import com.dwinovo.numen.core.pathing.movement.FlyTunables;
import com.dwinovo.numen.core.pathing.movement.Movement;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Creative-flight executor — the direct-fly half of creative-motion design v1's
 * hybrid (直飞执行器 + 独立轻量 3D-A*). Flies a companion body toward a live
 * {@link NavGoal} through the take-off → cruise → hover-land state machine,
 * driven the blueprint-mandated way: <b>every tick {@code setDeltaMovement}
 * with the desired velocity vector, locomotion inputs zeroed</b> (zza/xxa only
 * act horizontally, yya is overwritten by {@code travel}'s Y×0.6 — the direct
 * velocity write bypasses the whole input model and is the robust drive for a
 * server-side body). Vanilla {@code Player.travel} with {@code abilities.flying}
 * cancels gravity; because we rewrite the vector every tick, its friction never
 * accumulates.
 *
 * <p>Decision tree per tick (all mechanical — the flight judgement is
 * {@code getAbilities().mayfly}, never an LLM):
 * <ol>
 *   <li>ensure {@code abilities.flying} ({@code setGameMode(CREATIVE)} does NOT
 *       set it, and nothing auto-cancels it — manual set + {@code onUpdateAbilities});</li>
 *   <li>corridor raycast clear → cruise straight at the aim (take-off first
 *       climbs {@link FlyTunables#TAKEOFF_CLIMB_BLOCKS} for the cruise-altitude
 *       preference, skipped when the corridor is already clear or headroom ends);</li>
 *   <li>obstructed → {@link FlyPlanner} bounded 3D air search
 *       ({@link FlyTunables#AVOID_RADIUS}) and follow its waypoints;</li>
 *   <li>no air route → insta-break through the wall — but
 *       {@link BlockHelper#shouldAvoidBreaking} (containers/beds/functional
 *       blocks) is preserved in creative: a protected blocker first widens the
 *       detour search ({@link FlyTunables#AVOID_RADIUS_WIDE}) and then fails
 *       HONESTLY ("blocked by a player build") rather than grief;</li>
 *   <li>arrive: horizontal &lt; 0.35 and |Δy| &lt; 0.35 (or the goal's own
 *       {@code isAt}) → hover. {@code flying} stays ON (blueprint: 悬停不摘).</li>
 * </ol>
 *
 * <p>Aesthetics ride the existing look channel at zero tick cost: flight aims
 * the eyes via {@link InputDriver#lookAt} (the hard-aim rung), never writes yaw
 * for physics (velocity is direction-agnostic), and never fights the look
 * engine's input surface. The ground executor — including d3f15b5's liquid
 * float and landed-off-plan watchdogs — is a different object that simply does
 * not run while this one drives, so no underwater/landing replan can misfire
 * mid-flight; survival pathing is untouched by construction (the nav only
 * instantiates this executor behind the mayfly gate).
 */
public final class FlyPathExecutor {

    public enum Status {
        RUNNING,
        /** At the aim within the arrive tolerance — hovering, flying kept on. */
        ARRIVED,
        /** {@code mayfly} revoked (game-mode switch) — hand back to ground pathing. */
        UNAVAILABLE,
        /** No aerial way to make progress; see {@link #failReason()}. */
        FAILED
    }

    private enum Phase { TAKEOFF, CRUISE, BREAKTHROUGH }

    /** The flight gate — the whole creative fork hangs off this one mechanical test. */
    public static boolean available(ServerPlayer p) {
        return p.getAbilities().mayfly;
    }

    private final NumenPlayer player;
    private final Supplier<NavGoal> goalSupplier;
    private final double cruiseSpeed;
    /** Publishes a (synthetic or planned) FLY path to the owner's overlay. */
    private final Consumer<Path> vizSink;
    private final BlockDigger digger;

    private Phase phase = Phase.TAKEOFF;
    private double takeoffTargetY = Double.NaN;

    /** The adopted avoid route (Kind.FLY movements), or null when direct-flying. */
    private Path avoidPath;
    private int avoidIndex;
    /** Detour budget: avoid replans + breakthrough escalations this flight. */
    private int avoidEpisodes;

    /** Stall watchdog: commanded speed vs. actual displacement. */
    private Vec3 lastTickPos;
    private double lastCommandedSpeed;
    private int stallTicks;
    private int breakIdleTicks;

    /** Last aim cell published to the viz (re-publish only when it moves). */
    private BlockPos lastVizAim;

    private String failReason = "no aerial route";

    public FlyPathExecutor(NumenPlayer player, Supplier<NavGoal> goalSupplier, double speed,
                           Consumer<Path> vizSink) {
        this.player = player;
        this.goalSupplier = goalSupplier;
        this.cruiseSpeed = speed >= 1.0
                ? FlyTunables.SPRINT_CRUISE_SPEED : FlyTunables.CRUISE_SPEED;
        this.vizSink = vizSink;
        this.digger = new BlockDigger(player);
    }

    public String failReason() {
        return failReason;
    }

    public Status tick() {
        if (!available(player)) {
            // Game-mode switched under us. Do NOT touch flying here — the engine-side
            // pre-switch hook owns the safe descent; we just stop driving and let the
            // nav fall back to the ground pipeline from wherever the body ends up.
            digger.cancel();
            InputDriver.halt(player);
            return Status.UNAVAILABLE;
        }
        ensureFlying();

        NavGoal goal = goalSupplier.get();
        if (goal == null) {
            failReason = "target lost";
            hover();
            return Status.FAILED;
        }
        BlockPos aimCell = resolveAimCell(goal);
        Vec3 aimPoint = Vec3.atBottomCenterOf(aimCell);

        if (arrived(goal, aimPoint)) {
            // 悬停降落:hold station; flying stays ON by design (a grounded aim leaves the
            // body floating a hair over the block — vanilla never auto-cancels flight and
            // neither do we; the survival-switch descent belongs to the engine hook).
            hover();
            return Status.ARRIVED;
        }

        // Inputs are never the drive in flight — zero them every tick (the velocity
        // vector below is the drive). Sneak off: a flying vanilla player sneaking
        // descends; we don't want that input model half-engaged either.
        InputDriver.halt(player);
        player.setShiftKeyDown(false);

        // Stall watchdog: commanded motion but the body didn't move (a lateral clip
        // the two-ray corridor missed, a fence lip, …) → treat as obstructed.
        Vec3 now = player.position();
        if (lastTickPos != null && lastCommandedSpeed > 0.05
                && now.distanceTo(lastTickPos) < lastCommandedSpeed * 0.25) {
            if (++stallTicks > FlyTunables.STALL_TICKS) {
                stallTicks = 0;
                avoidPath = null;   // whatever plan we were on isn't working — re-decide
                Status blocked = onBlocked(aimCell, aimPoint, goal);
                if (blocked != null) return blocked;
            }
        } else {
            stallTicks = 0;
        }
        lastTickPos = now;
        lastCommandedSpeed = 0.0;

        switch (phase) {
            case TAKEOFF -> {
                return tickTakeoff(aimCell, aimPoint);
            }
            case CRUISE -> {
                return tickCruise(aimCell, aimPoint, goal);
            }
            case BREAKTHROUGH -> {
                return tickBreakthrough(aimCell, aimPoint, goal);
            }
        }
        return Status.RUNNING;
    }

    /** Zero all motion and hold station (flying, so gravity is off). */
    public void stop() {
        digger.cancel();
        InputDriver.halt(player);
        player.setShiftKeyDown(false);
        if (player.getAbilities().flying) {
            player.setDeltaMovement(Vec3.ZERO);   // hover; flying deliberately kept on
        }
    }

    // ---- phases ----

    /**
     * 起飞: set flying (done in {@link #tick}), then climb straight up a few blocks
     * for the cruise-altitude preference — skipped the moment the direct corridor is
     * clear (flying down into a canyon needs no climb) or headroom runs out (a cave
     * take-off cruises at ground level and lets avoidance handle the rest).
     */
    private Status tickTakeoff(BlockPos aimCell, Vec3 aimPoint) {
        if (Double.isNaN(takeoffTargetY)) {
            takeoffTargetY = player.getY() + FlyTunables.TAKEOFF_CLIMB_BLOCKS;
        }
        if (corridorClear(aimPoint) || player.getY() >= takeoffTargetY || !headroomClear()) {
            phase = Phase.CRUISE;
            return tickCruise(aimCell, aimPoint, goalSupplier.get());
        }
        publishDirectLine(aimCell);
        fly(new Vec3(player.getX(), takeoffTargetY + 0.5, player.getZ()), aimPoint);
        return Status.RUNNING;
    }

    /** 巡航: follow the avoid route if one is adopted, else fly the straight corridor;
     *  on obstruction, run the decision ladder (绕飞 → 瞬破 → 诚实失败). */
    private Status tickCruise(BlockPos aimCell, Vec3 aimPoint, NavGoal goal) {
        Vec3 wp = currentWaypoint(aimPoint);
        if (corridorClear(wp)) {
            if (avoidPath == null) {
                publishDirectLine(aimCell);
            }
            fly(wp, aimPoint);
            return Status.RUNNING;
        }
        // The immediate corridor is blocked — if we were following a detour, it went
        // stale (world changed / body drifted): drop it and re-decide from scratch.
        avoidPath = null;
        Status blocked = onBlocked(aimCell, aimPoint, goal);
        if (blocked != null) return blocked;
        // A plan was adopted (avoid route or breakthrough) — drive it this same tick.
        if (phase == Phase.BREAKTHROUGH) {
            return tickBreakthrough(aimCell, aimPoint, goal);
        }
        fly(currentWaypoint(aimPoint), aimPoint);
        return Status.RUNNING;
    }

    /**
     * 瞬破穿墙: hover before the wall and insta-break the corridor open, creeping
     * forward as it clears. Every cell is re-vetoed here — a protected block
     * (container/bed/functional) is never broken even in creative; hitting one
     * mid-tunnel re-runs the widened detour ladder instead.
     */
    private Status tickBreakthrough(BlockPos aimCell, Vec3 aimPoint, NavGoal goal) {
        if (corridorClear(currentWaypoint(aimPoint))) {
            // Tunnel is open (at least as far as the lookahead) — back to cruising.
            phase = Phase.CRUISE;
            digger.cancel();
            breakIdleTicks = 0;
            return tickCruise(aimCell, aimPoint, goal);
        }
        BlockPos blocker = firstBlockerOnLine(aimPoint);
        if (blocker == null) {
            // The rays disagree with the corridor check (seam case) — nudge forward slowly.
            flyCapped(aimPoint, FlyTunables.BREAKTHROUGH_CREEP_SPEED);
            return Status.RUNNING;
        }
        if (!diggable(blocker)) {
            phase = Phase.CRUISE;
            digger.cancel();
            Status blocked = onBlocked(aimCell, aimPoint, goal);
            return blocked != null ? blocked : Status.RUNNING;
        }
        double reach = player.blockInteractionRange();
        Vec3 blockerCenter = Vec3.atCenterOf(blocker);
        if (player.getEyePosition().distanceTo(blockerCenter) > reach - 0.5) {
            // Not in reach yet — creep toward it (the corridor behind us is open).
            flyCapped(blockerCenter, FlyTunables.BREAKTHROUGH_CREEP_SPEED);
        } else {
            hover();
            if (digger.dig(blocker)) {          // creative: instabreak on START (verified)
                breakIdleTicks = 0;
                return Status.RUNNING;
            }
        }
        if (++breakIdleTicks > FlyTunables.BREAKTHROUGH_STALL_TICKS) {
            failReason = "couldn't open a way through (wedged while tunnelling at "
                    + blocker.toShortString() + ")";
            hover();
            return Status.FAILED;
        }
        return Status.RUNNING;
    }

    // ---- obstruction decision ladder ----

    /**
     * The blueprint's obstruction ladder, in its stated priority (绕行优先于瞬破):
     * bounded 3D detour first; then, if every blocker on the line is honestly
     * breakable, the insta-break tunnel; a protected/unbreakable blocker instead
     * widens the detour once and then fails with the honest reason. Returns a
     * terminal status, or null when a plan (avoid route / breakthrough) was adopted.
     */
    private Status onBlocked(BlockPos aimCell, Vec3 aimPoint, NavGoal goal) {
        if (goal == null) {
            failReason = "target lost";
            hover();
            return Status.FAILED;
        }
        if (++avoidEpisodes > FlyTunables.MAX_AVOID_EPISODES) {
            failReason = "aerial route exhausted after "
                    + FlyTunables.MAX_AVOID_EPISODES + " detours";
            hover();
            return Status.FAILED;
        }
        BlockPos feet = feet();
        Path detour = FlyPlanner.plan(player.level(), feet, aimCell, goal,
                FlyTunables.AVOID_RADIUS, FlyTunables.AVOID_NODE_CAP, ticksPerBlock());
        if (detour != null) {
            adoptDetour(detour);
            return null;
        }
        LineBlockers lb = scanLine(aimPoint);
        if (lb.protectedBlocker == null && !lb.unbreakable && lb.any) {
            Constants.LOG.info("[numen-fly] no detour in r{} — breakthrough toward {}",
                    FlyTunables.AVOID_RADIUS, aimCell.toShortString());
            phase = Phase.BREAKTHROUGH;
            breakIdleTicks = 0;
            return null;
        }
        // Protected / unbreakable terrain on the line — one widened detour attempt,
        // then the honest report (会飞也不拆玩家的家).
        detour = FlyPlanner.plan(player.level(), feet, aimCell, goal,
                FlyTunables.AVOID_RADIUS_WIDE, FlyTunables.AVOID_NODE_CAP_WIDE, ticksPerBlock());
        if (detour != null) {
            adoptDetour(detour);
            return null;
        }
        if (lb.protectedBlocker != null) {
            failReason = "blocked by a player build ("
                    + blockName(lb.protectedBlocker) + " at "
                    + lb.protectedBlocker.toShortString()
                    + ") — I won't break it, and found no way around within "
                    + FlyTunables.AVOID_RADIUS_WIDE + " blocks";
        } else if (lb.unbreakable) {
            failReason = "no aerial route (unbreakable terrain in the way)";
        } else {
            failReason = "no aerial route within " + FlyTunables.AVOID_RADIUS_WIDE + " blocks";
        }
        hover();
        return Status.FAILED;
    }

    private void adoptDetour(Path detour) {
        avoidPath = detour;
        avoidIndex = 0;
        phase = Phase.CRUISE;
        if (vizSink != null) {
            vizSink.accept(detour);
        }
        Constants.LOG.info("[numen-fly] detour adopted: {} waypoints{} toward {}",
                detour.movements.size(), detour.partial ? " (partial)" : "",
                detour.end.toShortString());
    }

    // ---- flight primitives ----

    /** flying=true is manual by design — setGameMode(CREATIVE) never sets it and
     *  nothing auto-cancels it; we assert it every tick (cheap once set). */
    private void ensureFlying() {
        var abilities = player.getAbilities();
        if (!abilities.flying) {
            abilities.flying = true;
            player.onUpdateAbilities();
        }
    }

    /** Hold station: zero the vector; flying (gravity off) makes that a hover. */
    private void hover() {
        player.setDeltaMovement(Vec3.ZERO);
        lastCommandedSpeed = 0.0;
    }

    /**
     * The drive: write the velocity vector straight at {@code point}, decelerating
     * on approach (the flight twin of the ground path-end sprint ease-off — never
     * overshoot the hover point), vertical component clamped to
     * {@link FlyTunables#VERTICAL_SPEED}. Eyes ride the look system's hard-aim rung
     * toward the FINAL aim ({@code lookTarget}), not the intermediate waypoint, so
     * the head reads as intent, not zigzag.
     */
    private void fly(Vec3 point, Vec3 lookTarget) {
        Vec3 to = point.subtract(player.position());
        double dist = to.length();
        if (dist < FlyTunables.HOVER_DEADZONE) {
            hover();
            return;
        }
        double speed = Math.min(cruiseSpeed,
                Math.max(0.08, dist * FlyTunables.APPROACH_DECEL_PER_BLOCK));
        Vec3 v = to.scale(speed / dist);
        double vy = Math.max(-FlyTunables.VERTICAL_SPEED,
                Math.min(FlyTunables.VERTICAL_SPEED, v.y));
        player.setDeltaMovement(new Vec3(v.x, vy, v.z));
        lastCommandedSpeed = speed;
        InputDriver.lookAt(player, lookTarget.add(0.0, 0.9, 0.0));   // eye-ish height: natural gaze
    }

    /** As {@link #fly} but with an explicit speed cap (breakthrough creep). */
    private void flyCapped(Vec3 point, double cap) {
        Vec3 to = point.subtract(player.position());
        double dist = to.length();
        if (dist < FlyTunables.HOVER_DEADZONE) {
            hover();
            return;
        }
        double speed = Math.min(cap, dist * FlyTunables.APPROACH_DECEL_PER_BLOCK + 0.05);
        Vec3 v = to.scale(speed / dist);
        double vy = Math.max(-FlyTunables.VERTICAL_SPEED,
                Math.min(FlyTunables.VERTICAL_SPEED, v.y));
        player.setDeltaMovement(new Vec3(v.x, vy, v.z));
        lastCommandedSpeed = speed;
        InputDriver.lookAt(player, point);
    }

    // ---- aim / arrival ----

    /**
     * Resolve where to fly for goals whose {@code center()} uses sentinel axes
     * (column goals carry y=0, yLevel carries x/z=0): probe the center against
     * variants that keep the body's own altitude / own column and take the one the
     * goal's OWN heuristic scores best — ties prefer keeping our coordinates, so a
     * column goal is approached at cruise altitude, a y-level goal in place.
     * Purely mechanical, goal-agnostic.
     */
    private BlockPos resolveAimCell(NavGoal goal) {
        BlockPos feet = feet();
        BlockPos c = goal.center();
        BlockPos[] candidates = {
                new BlockPos(c.getX(), feet.getY(), c.getZ()),   // keep own altitude
                new BlockPos(feet.getX(), c.getY(), feet.getZ()),// keep own column
                c
        };
        BlockPos best = c;
        double bestH = Double.MAX_VALUE;
        for (BlockPos cand : candidates) {
            double h = goal.heuristic(cand);
            if (h < bestH - 1.0e-6) {
                bestH = h;
                best = cand;
            }
        }
        return best;
    }

    /** Blueprint arrival: the goal's own cell test, or horizontal &lt; 0.35 and |Δy| &lt; 0.35. */
    private boolean arrived(NavGoal goal, Vec3 aimPoint) {
        if (goal.isAt(feet())) return true;
        double dx = aimPoint.x - player.getX();
        double dz = aimPoint.z - player.getZ();
        double dh = Math.sqrt(dx * dx + dz * dz);
        double dv = Math.abs(aimPoint.y - player.getY());
        return dh < FlyTunables.ARRIVE_HORIZONTAL && dv < FlyTunables.ARRIVE_VERTICAL;
    }

    /** The point to fly at this tick: the next detour waypoint, else the aim itself. */
    private Vec3 currentWaypoint(Vec3 aimPoint) {
        while (avoidPath != null) {
            if (avoidIndex >= avoidPath.movements.size()) {
                avoidPath = null;
                break;
            }
            Vec3 wp = Vec3.atBottomCenterOf(avoidPath.movements.get(avoidIndex).dest);
            if (player.position().distanceTo(wp) < FlyTunables.WAYPOINT_REACH) {
                avoidIndex++;
                continue;
            }
            return wp;
        }
        return aimPoint;
    }

    // ---- corridor / line geometry ----

    /**
     * Is the flight corridor toward {@code to} clear for the body? Two rays —
     * feet-level and eye-level — capped at {@link FlyTunables#CORRIDOR_LOOKAHEAD}
     * (obstructions further out are handled as we get closer; also keeps all world
     * reads in loaded terrain). {@code COLLIDER} shapes only: grass/torches don't block a
     * flight; fluids don't block the ray (water is flyable, only penalised).
     */
    private boolean corridorClear(Vec3 to) {
        Vec3 feetFrom = player.position().add(0.0, 0.1, 0.0);
        Vec3 dirFull = to.subtract(feetFrom);
        double dist = dirFull.length();
        if (dist < 1.0e-4) return true;
        Vec3 step = dirFull.scale(Math.min(dist, FlyTunables.CORRIDOR_LOOKAHEAD) / dist);
        if (!rayClear(feetFrom, feetFrom.add(step))) return false;
        Vec3 eyeFrom = player.getEyePosition();
        return rayClear(eyeFrom, eyeFrom.add(step));
    }

    private boolean rayClear(Vec3 from, Vec3 to) {
        return player.level().clip(new ClipContext(from, to,
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
                .getType() == HitResult.Type.MISS;
    }

    /** The first colliding cell on the line toward the aim (feet ray, then eye ray),
     *  within the lookahead — the breakthrough dig target. Null when both rays miss. */
    private BlockPos firstBlockerOnLine(Vec3 aimPoint) {
        Vec3 feetFrom = player.position().add(0.0, 0.1, 0.0);
        Vec3 dirFull = aimPoint.subtract(feetFrom);
        double dist = dirFull.length();
        if (dist < 1.0e-4) return null;
        Vec3 step = dirFull.scale(Math.min(dist, FlyTunables.CORRIDOR_LOOKAHEAD) / dist);
        var feetHit = player.level().clip(new ClipContext(feetFrom, feetFrom.add(step),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (feetHit.getType() == HitResult.Type.BLOCK) return feetHit.getBlockPos();
        Vec3 eyeFrom = player.getEyePosition();
        var eyeHit = player.level().clip(new ClipContext(eyeFrom, eyeFrom.add(step),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return eyeHit.getType() == HitResult.Type.BLOCK ? eyeHit.getBlockPos() : null;
    }

    private record LineBlockers(boolean any, BlockPos protectedBlocker, boolean unbreakable) {}

    /**
     * Classify every blocking cell along the (lookahead-capped) line: is any of
     * them protected ({@link BlockHelper#shouldAvoidBreaking} — the do-not-grief
     * set the blueprint keeps in creative) or plain unbreakable/hazardous? Samples
     * the feet- and head-line cell columns block by block.
     */
    private LineBlockers scanLine(Vec3 aimPoint) {
        Level level = player.level();
        Vec3 from = player.position().add(0.0, 0.1, 0.0);
        Vec3 dirFull = aimPoint.subtract(from);
        double dist = dirFull.length();
        if (dist < 1.0e-4) return new LineBlockers(false, null, false);
        double len = Math.min(dist, FlyTunables.CORRIDOR_LOOKAHEAD);
        Vec3 dir = dirFull.scale(1.0 / dist);
        boolean any = false;
        boolean unbreakable = false;
        BlockPos protectedBlocker = null;
        BlockPos last = null;
        for (double t = 0.5; t <= len; t += 0.5) {
            Vec3 p = from.add(dir.scale(t));
            BlockPos cell = BlockPos.containing(p.x, p.y, p.z);
            if (cell.equals(last)) continue;
            last = cell;
            for (BlockPos c : new BlockPos[]{cell, cell.above()}) {
                if (FlyPlanner.bodyFlyable(level, c)) continue;   // body fits here
                if (level.getBlockState(c).getCollisionShape(level, c).isEmpty()
                        && !level.getBlockState(c).getFluidState().isEmpty()) {
                    continue;   // fluid — not a break target
                }
                if (level.getBlockState(c).isAir()) continue;
                any = true;
                if (BlockHelper.shouldAvoidBreaking(level, c) && protectedBlocker == null) {
                    protectedBlocker = c.immutable();
                }
                if (!BlockHelper.isBreakable(level, c)) {
                    unbreakable = true;
                }
            }
        }
        return new LineBlockers(any, protectedBlocker, unbreakable);
    }

    /** May the flight tunnel break this cell? The creative do-not-grief contract:
     *  breakable, not a hazard, doesn't release a fluid, and NOT a protected
     *  functional block — same vetoes the ground cost model keeps in creative. */
    private boolean diggable(BlockPos pos) {
        Level level = player.level();
        return BlockHelper.isBreakable(level, pos)
                && !BlockHelper.isHazard(level, pos)
                && !BlockHelper.breakWouldCreateFlow(level, pos)
                && !BlockHelper.shouldAvoidBreaking(level, pos);
    }

    // ---- small helpers ----

    private BlockPos feet() {
        return BlockHelper.playerFeet(player.level(), player.getX(), player.getY(), player.getZ());
    }

    private double ticksPerBlock() {
        return 1.0 / cruiseSpeed;
    }

    /** Overhead body cell still open (take-off climb guard). */
    private boolean headroomClear() {
        return FlyPlanner.bodyFlyable(player.level(), feet().above());
    }

    private String blockName(BlockPos pos) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(player.level().getBlockState(pos).getBlock()).getPath();
    }

    /** Publish the straight flight line to the owner's overlay as a one-edge FLY
     *  path — re-published only when the aim cell actually moves (no per-tick spam). */
    private void publishDirectLine(BlockPos aimCell) {
        if (vizSink == null) return;
        if (lastVizAim != null && lastVizAim.distSqr(aimCell) <= 4.0) return;
        lastVizAim = aimCell.immutable();
        BlockPos start = feet();
        if (start.equals(aimCell)) return;
        double cost = Math.sqrt(start.distSqr(aimCell)) * ticksPerBlock();
        vizSink.accept(new Path(start, aimCell,
                List.of(new Movement(Movement.Kind.FLY, start, aimCell, cost, List.of(), null)),
                false));
    }
}
