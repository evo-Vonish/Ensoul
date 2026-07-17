package com.dwinovo.numen.core.pathing.exec;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The <strong>turtle-up muscle</strong> — the fourth combat option for a companion that can neither win nor run
 * (engagement verdict {@code CORNERED} with a lethal damage race): burrow a one-wide shaft straight down a couple
 * of blocks, cap the hole overhead with a block, and hunker down until the threat passes. A deterministic,
 * code-closed loop (no A* search, no LLM) shared by two callers: the reflex layer (spinal cord) fires it
 * automatically as a last-ditch survival act, and the {@code turtle_up} tool lets the brain order it directly.
 *
 * <h2>Closed loop</h2>
 * <ul>
 *   <li><b>DIG</b> — break the floor block underfoot and drop into it, repeating down to {@link #targetDepth}
 *       blocks. Only hand-/tool-breakable floor is dug (dirt/sand/gravel bare-handed, stone with a pickaxe);
 *       a fluid / bedrock / too-slow floor stops the descent early and we seal at whatever depth we reached
 *       (a shallow pit still cuts the number of mobs that can reach the body).</li>
 *   <li><b>SEAL</b> — place a solid block in the cell just above the head, closing the shaft. No block in the
 *       pack → skip the cap and just crouch in the pit ("只蹲坑").</li>
 *   <li><b>HOLED</b> — planted, sneaking, facing up; the caller re-evaluates on its own cadence and, when the
 *       coast is clear, calls {@link #breakOut()}.</li>
 *   <li><b>BREAKOUT</b> — reuse {@link PillarDrive} to dig the cap and pillar back up to the start height, then
 *       report {@link Status#DONE}. Best-effort: if it can't climb (no scaffold), it still finishes so the
 *       brain regains the body.</li>
 * </ul>
 *
 * <p>Stateful + ticked like {@link PillarDrive} / {@link BlockDigger}: the owner calls {@link #tick()} each server
 * tick and reacts to the {@link Status}. Server-thread only. Holds no entity references.
 */
public final class TurtleDrive {

    /** Outcome of one tick. */
    public enum Status {
        /** Digging down or placing the cap — work in progress. */
        WORKING,
        /** Sealed (or crouching in the pit if no block was available) and holding — safe to re-evaluate. */
        SEALED,
        /** Breaking out — digging the cap / climbing back up after {@link #breakOut()}. */
        EMERGING,
        /** Fully out (or the body regained control); the episode may end. */
        DONE
    }

    private enum Phase { DIG, SEAL, HOLED, BREAKOUT, DONE }

    /** Default burrow depth (blocks). Depth 3 puts the cap flush below the original surface, where the shaft
     *  walls give it a solid support face; a shallower stop still seals when a wall neighbour is solid. */
    private static final int DEFAULT_DEPTH = 3;
    private static final int MIN_DEPTH = 2;
    /** Give up descending through the current floor block after this many ticks (catches hand-mining stone). */
    private static final int DIG_BLOCK_TIMEOUT = 60;
    /** Bounded cap-placement attempts before we accept an un-capped crouch. */
    private static final int SEAL_MAX_TICKS = 40;

    private final NumenPlayer player;
    private final int targetDepth;
    private final BlockDigger digger;
    private PillarDrive climber;

    private Phase phase = Phase.DIG;
    private int startY = Integer.MIN_VALUE;
    private int lastFeetY = Integer.MIN_VALUE;
    private int digBlockTicks = 0;
    private int sealTicks = 0;
    private boolean capped = false;

    public TurtleDrive(NumenPlayer player) {
        this(player, DEFAULT_DEPTH);
    }

    public TurtleDrive(NumenPlayer player, int depth) {
        this.player = player;
        this.targetDepth = Math.max(MIN_DEPTH, depth);
        this.digger = new BlockDigger(player);
    }

    /** Request a break-out from the burrow (threat gone / daylight / owner arrived). Idempotent. */
    public void breakOut() {
        if (phase != Phase.BREAKOUT && phase != Phase.DONE) {
            phase = Phase.BREAKOUT;
            if (climber == null) climber = new PillarDrive(player);
        }
    }

    /** True once the shaft is capped (or the crouch fallback is holding). */
    public boolean isHoled() {
        return phase == Phase.HOLED;
    }

    /** Advance the burrow one tick. */
    public Status tick() {
        Level level = player.level();
        BlockPos feet = feet();
        if (startY == Integer.MIN_VALUE) {
            startY = feet.getY();
            lastFeetY = startY;
        }
        return switch (phase) {
            case DIG -> tickDig(level, feet);
            case SEAL -> tickSeal(level, feet);
            case HOLED -> tickHoled(feet);
            case BREAKOUT -> tickBreakout();
            case DONE -> Status.DONE;
        };
    }

    private Status tickDig(Level level, BlockPos feet) {
        int depth = startY - feet.getY();
        if (feet.getY() < lastFeetY) {           // descended a block → reset the per-block stall timer
            lastFeetY = feet.getY();
            digBlockTicks = 0;
        }
        if (depth >= targetDepth) {              // reached the target depth → seal
            digger.cancel();
            phase = Phase.SEAL;
            return Status.WORKING;
        }
        BlockPos floor = feet.below();
        if (!player.onGround()) {                // mid-fall after a break — wait to land
            InputDriver.halt(player);
            return Status.WORKING;
        }
        if (!diggableFloor(level, floor)) {      // fluid / bedrock / air-over-void / protected → seal where we are
            digger.cancel();                     // (even at shallow depth: cap if a wall supports it, else crouch)
            phase = Phase.SEAL;
            return Status.WORKING;
        }
        if (++digBlockTicks > DIG_BLOCK_TIMEOUT) {   // too slow to break by hand (stone, no pickaxe) → seal here
            digger.cancel();
            phase = Phase.SEAL;
            return Status.WORKING;
        }
        player.setShiftKeyDown(false);
        digger.dig(floor);                       // native progressive break; body drops into the hole on completion
        return Status.WORKING;
    }

    private Status tickSeal(Level level, BlockPos feet) {
        InputDriver.halt(player);
        BlockPos cap = feet.above(2);            // the cell one block above the head
        if (!BlockHelper.canWalkThrough(level, cap)) {   // already solid (or we just placed it) → sealed
            capped = true;
            phase = Phase.HOLED;
            return Status.SEALED;
        }
        int slot = sealBlockSlot();
        if (slot < 0 || ++sealTicks > SEAL_MAX_TICKS) {  // no block, or gave up placing → crouch, unsealed
            phase = Phase.HOLED;
            return Status.SEALED;
        }
        placeCap(cap, slot);
        return Status.WORKING;
    }

    private Status tickHoled(BlockPos feet) {
        InputDriver.halt(player);
        player.setShiftKeyDown(true);            // stay crouched — never step off into the pit wall
        InputDriver.lookAt(player, player.getEyePosition().add(0.0, 2.0, 0.0));   // face the cap (up)
        return Status.SEALED;
    }

    private Status tickBreakout() {
        if (climber == null) climber = new PillarDrive(player);
        BlockPos feet = feet();
        if (player.onGround() && feet.getY() >= startY) {   // back at the surface height → done
            finishClimb();
            return Status.DONE;
        }
        switch (climber.tick()) {
            case ROSE, RISING -> { return Status.EMERGING; }
            // Can't climb out (no scaffold) or the cap is unbreakable/fluid: dig the cap if we can, then finish
            // best-effort so the brain regains the body rather than looping forever in the pit.
            case NEED_SCAFFOLD, CEILING_FLUID, CEILING_UNBREAKABLE -> {
                finishClimb();
                return Status.DONE;
            }
        }
        return Status.EMERGING;
    }

    /** Place a solid cap at {@code cap}, natively: look up, honest raycast for a support face, hold + use. */
    private void placeCap(BlockPos cap, int slot) {
        InputDriver.lookAt(player, Vec3.atCenterOf(cap));
        BlockHitResult hit = Placement.resolve(player, cap, false);
        if (hit == null) return;                 // no reachable support face this tick — retry (bounded)
        player.holdInHand(slot);
        Interaction.useBlock(player, hit, InteractionHand.MAIN_HAND).tick();
    }

    private void finishClimb() {
        if (climber != null) { climber.stop(); climber = null; }
        digger.cancel();
        InputDriver.halt(player);
        player.setShiftKeyDown(false);
        phase = Phase.DONE;
    }

    /** Release all inputs and abandon any in-progress dig/climb — the interrupt path (episode end / seized body). */
    public void stop() {
        digger.cancel();
        if (climber != null) { climber.stop(); climber = null; }
        InputDriver.halt(player);
        player.setShiftKeyDown(false);
    }

    /** True depth reached so far (blocks below the start), for the event blurb / task settlement. */
    public int depthReached() {
        return startY == Integer.MIN_VALUE ? 0 : Math.max(0, startY - feet().getY());
    }

    /** Whether the shaft ended up capped (vs. an open crouch). */
    public boolean capped() {
        return capped;
    }

    // ---- helpers ----

    /** A floor block we may burrow through: solid, breakable, not a fluid and not a block we won't grief. */
    private static boolean diggableFloor(Level level, BlockPos floor) {
        BlockState s = level.getBlockState(floor);
        if (s.isAir()) return false;
        if (!s.getFluidState().isEmpty()) return false;                 // never dig into water/lava underfoot
        if (!BlockHelper.isBreakable(level, floor)) return false;       // bedrock and the like
        return !BlockHelper.shouldAvoidBreaking(level, floor);          // containers / stations — leave them
    }

    /** Inventory slot of a placeable full-cube solid block for the cap, or -1 if the pack holds none. */
    private int sealBlockSlot() {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (isSealBlock(inv.getItem(i))) return i;
        }
        return -1;
    }

    /** A {@link BlockItem} whose block forms a full collision cube (so the cap actually closes the shaft). */
    private static boolean isSealBlock(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi)) return false;
        BlockState def = bi.getBlock().defaultBlockState();
        return Block.isShapeFullBlock(def.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
    }

    private BlockPos feet() {
        return BlockHelper.playerFeet(player.level(), player.getX(), player.getY(), player.getZ());
    }
}
