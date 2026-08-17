package com.dwinovo.numen.core.pathing.exec;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.calc.NavContext;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A deterministic, in-place vertical climb — the reusable engine behind the
 * {@code pillar_up} and {@code escape_to_surface} muscles. It rises the body ONE
 * block per successful rung, driving the same Baritone {@code MovementPillar}
 * physics the pathfinder's {@code PlayerPathExecutor#drivePillar}/{@code placeUnderfoot}
 * already prove in-game: sneak so the body never steps off the column, recentre,
 * jump from the ground, and at the apex place a scaffold block in the cell just
 * vacated so the body lands one higher. When a block blocks the head-room it is
 * broken first with the native {@link BlockDigger}.
 *
 * <p>Crucially it does NOT run the A* planner: a straight climb needs no search,
 * which is the whole point — the pit accident stranded the companion because a
 * vertical {@code move_to} blew the search budget. Here the destination is simply
 * "up", so there is nothing to search.
 *
 * <p>Stateful + ticked like {@link BlockDigger} / {@link PlaceManeuver}: the owning
 * task calls {@link #tick()} each server tick and reacts to the returned
 * {@link Result}. The task owns the target height and (for escape) the sidestep
 * policy; this engine owns one rung.
 */
public final class PillarDrive {

    /** Outcome of one tick of climbing. */
    public enum Result {
        /** Working on the current rung (jumping, placing, or digging head-room). */
        RISING,
        /** The body climbed one block and is standing on the new scaffold. */
        ROSE,
        /** No scaffolding block in inventory to stand up on. */
        NEED_SCAFFOLD,
        /** The head-room cell is a fluid (or breaking it would loose lava) — never dig it. */
        CEILING_FLUID,
        /** The head-room cell is a dead end: unbreakable (bedrock), a block we won't grief, or
         *  breakable-but-loaded — sand/gravel stacked on it that would bury the shaft. */
        CEILING_UNBREAKABLE
    }

    private final NumenPlayer player;
    private final BlockDigger digger;
    /** Feet Y at the start of the current rung; a rung completes when the body settles one higher. */
    private int baseFeetY = Integer.MIN_VALUE;

    public PillarDrive(NumenPlayer player) {
        this.player = player;
        this.digger = new BlockDigger(player);
    }

    /** Classify the cell the head must rise into next (feet + 2) — for a caller that wants to
     *  decide BEFORE committing (e.g. escape_to_surface's sidestep check). */
    public MotorGeometry.Head ceilingClass() {
        return classifyHead(player.level(), feet().above(2));
    }

    /** Advance the current rung by one tick. */
    public Result tick() {
        Level level = player.level();
        BlockPos feet = feet();
        if (baseFeetY == Integer.MIN_VALUE) {
            baseFeetY = feet.getY();
        }
        // Rung complete: the feet climbed above where this rung began AND settled on solid ground
        // (the onGround gate rejects the jump apex, mirroring PlayerPathExecutor.arrived's pillar case).
        if (feet.getY() > baseFeetY && player.onGround()
                && BlockHelper.canWalkOn(level, feet.below())) {
            baseFeetY = feet.getY();
            InputDriver.halt(player);        // shed any residual momentum before the next rung
            player.setShiftKeyDown(false);
            return Result.ROSE;
        }

        // Head-room: the cell the body's head sweeps into as it rises (Baritone MovementPillar newHead).
        BlockPos ceiling = feet.above(2);
        MotorGeometry.Head head = classifyHead(level, ceiling);
        switch (head) {
            case FLUID -> { return Result.CEILING_FLUID; }
            // FALLING rides the UNBREAKABLE result deliberately: callers already treat that as
            // "this column is a dead end, sidestep or abort", which is exactly right for a gravel
            // ceiling — and it needs no new Result constant for every caller to re-handle.
            case UNBREAKABLE, FALLING -> { return Result.CEILING_UNBREAKABLE; }
            case BREAKABLE -> {
                // Break the ceiling first — sneak so a stray input can't walk us off the column.
                player.setShiftKeyDown(true);
                digger.dig(ceiling);
                return Result.RISING;
            }
            case CLEAR -> { /* fall through to the climb */ }
        }

        if (scaffoldSlot() < 0) {
            player.setShiftKeyDown(false);
            return Result.NEED_SCAFFOLD;
        }
        drivePillarRung(feet);
        return Result.RISING;
    }

    /**
     * One rung of Baritone {@code MovementPillar} (dry column): sneak, recentre on the column,
     * jump from a near-standstill on the ground, and at the apex place a scaffold block in the
     * cell just vacated so the body lands a block higher.
     */
    private void drivePillarRung(BlockPos src) {
        player.setShiftKeyDown(true);   // sneak: never step off the column
        if (horizontalDistTo(src) > 0.17) {
            InputDriver.stepToward(player, Vec3.atBottomCenterOf(src), false);
        } else {
            InputDriver.halt(player);
            // Jump only when nearly still AND still below the rung's top (feet Y still at the base).
            if (player.onGround() && horizontalSpeedSqr() < 0.0025
                    && player.getY() < src.getY() + 1.0) {
                InputDriver.jump(player);
            }
        }
        // Past the apex of the hop (clear of the src cell): place the block underfoot.
        if (player.getY() > src.getY() + 1.1) {
            placeUnderfoot(src);
        }
    }

    /** Place a scaffold block at {@code cell} against the solid block directly below it — the
     *  pillar apex place, native (look straight down + crouch-confirm + honest raycast + useItemOn).
     *  A 1:1 port of {@code PlayerPathExecutor#placeUnderfoot}. */
    private void placeUnderfoot(BlockPos cell) {
        int slot = scaffoldSlot();
        if (slot < 0) return;
        if (!Placement.canPlaceAgainst(player.level(), cell.below())) return;
        InputDriver.lookAt(player, Vec3.atBottomCenterOf(cell));   // look straight down at the support's top
        if (!player.isCrouching()) return;                          // crouch-confirm (sneak lands next tick)
        BlockHitResult hit = Placement.resolve(player, cell, true); // honest raycast only — no fabricated hit
        if (hit == null) return;
        player.holdInHand(slot);                                    // real hotbar-select / swap-to-hand
        Interaction.useBlock(player, hit, InteractionHand.MAIN_HAND).tick();
    }

    /** Classify a head-room cell: fluid (never dig) / clear / unbreakable / breakable. Static so a
     *  caller (escape_to_surface) can probe a candidate sidestep column without a body there yet. */
    public static MotorGeometry.Head classifyHead(Level level, BlockPos c) {
        BlockState s = level.getBlockState(c);
        if (!s.getFluidState().isEmpty()) {
            return MotorGeometry.Head.FLUID;                 // water/lava overhead — never dig into it
        }
        if (BlockHelper.canWalkThrough(level, c)) {
            return MotorGeometry.Head.CLEAR;
        }
        if (!BlockHelper.isBreakable(level, c) || BlockHelper.shouldAvoidBreaking(level, c)) {
            return MotorGeometry.Head.UNBREAKABLE;           // bedrock, or a container/station we won't grief
        }
        if (wouldExposeLava(level, c)) {
            return MotorGeometry.Head.FLUID;                 // breaking it would let lava pour into the shaft
        }
        if (BlockHelper.breakReleasesFallingBlock(level, c)) {
            // Sand/gravel resting on this cell. Break it and the stack drops into the shaft we are
            // standing in — and since the climber just digs the next rung, it keeps digging into
            // the falling column and buries itself. Baritone's dontMineUnderFallingBlock veto.
            return MotorGeometry.Head.FALLING;
        }
        return MotorGeometry.Head.BREAKABLE;
    }

    /** Would breaking {@code c} open the cell to lava on a side or above (which would then flow in)? */
    private static boolean wouldExposeLava(Level level, BlockPos c) {
        for (Direction d : Direction.values()) {
            if (d == Direction.DOWN) continue;               // a fluid below can't flow up into the emptied cell
            if (level.getBlockState(c.relative(d)).getFluidState()
                    .is(net.minecraft.tags.FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }

    /** The inventory slot of a scaffold block (cobble/dirt/…), or -1 if none. */
    private int scaffoldSlot() {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (NavContext.isScaffold(inv.getItem(i))) return i;
        }
        return -1;
    }

    private BlockPos feet() {
        return BlockHelper.playerFeet(player.level(), player.getX(), player.getY(), player.getZ());
    }

    private double horizontalDistTo(BlockPos cell) {
        double dx = (cell.getX() + 0.5) - player.getX();
        double dz = (cell.getZ() + 0.5) - player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private double horizontalSpeedSqr() {
        var v = player.getDeltaMovement();
        return v.x * v.x + v.z * v.z;
    }

    /** Release sneak, halt locomotion, abandon any in-progress ceiling dig. */
    public void stop() {
        digger.cancel();
        InputDriver.halt(player);
        player.setShiftKeyDown(false);
    }
}
