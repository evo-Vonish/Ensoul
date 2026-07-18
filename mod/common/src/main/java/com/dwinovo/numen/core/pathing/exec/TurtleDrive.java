package com.dwinovo.numen.core.pathing.exec;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
 *       blocks. Below {@link #MIN_DEPTH} the descent is fought for hard (the long
 *       {@link #DIG_BLOCK_TIMEOUT_SHALLOW} budget covers bare-hand stone) because depth is the only thing that
 *       removes the melee threat — see SEAL; at/above {@link #MIN_DEPTH} a slow floor gives up after the short
 *       {@link #DIG_BLOCK_TIMEOUT}. A fluid / bedrock / protected floor stops the descent at any depth.</li>
 *   <li><b>SEAL</b> — close the shell and PROVE it closed. "Sealed" is a <em>block-measured</em> verdict, never
 *       a state-machine assumption: the cap cell above the head AND the four side cells at BOTH body levels
 *       (head ring + feet ring) must all be full collision cubes, AND the shaft must be at least
 *       {@link #MIN_DEPTH} deep. Mechanical basis (verified against this version's mapped sources):
 *       {@code Mob#getAttackBoundingBox} inflates the attacker's bounding box HORIZONTALLY only
 *       ({@code inflate(r, 0.0, r)}, r≈0.83), so two blocks of depth removes the Y-overlap with any melee mob
 *       standing on adjacent ground — no side block can substitute for that; and
 *       {@code MeleeAttackGoal#canPerformAttack} additionally requires {@code hasLineOfSight}, which the solid
 *       cap + rings cut. Deepening therefore outranks patching below {@link #MIN_DEPTH} — and also whenever the
 *       pack holds no seal block or a gap cell offers no placement support at this depth (e.g. the cap floating
 *       above a 1-deep pit, the give-up path behind field death #3); otherwise gaps are patched one native
 *       placement per tick, cap first. If neither digging nor placing can close the shell, the drive settles
 *       EXPOSED — honestly: {@link #sealedTight()} stays false and {@link #openSealGaps()} reports the holes,
 *       so the caller can say "未能完全密封" instead of reporting a safety that does not exist.</li>
 *   <li><b>HOLED</b> — planted, sneaking, facing up; re-audits the shell every {@link #HOLED_AUDIT_PERIOD}
 *       ticks and re-enters SEAL the moment a NEW hole appears (broken cap / blast); {@link #reverify()} lets
 *       the owner force that re-audit this tick (a landed hit while holed is proof of a hole). The caller
 *       re-evaluates on its own cadence and, when the coast is clear, calls {@link #breakOut()}.</li>
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
        /** Digging down or placing blocks — work in progress. */
        WORKING,
        /** Holding in the pit — check {@link #sealedTight()} for the block-measured verdict (a give-up hold
         *  with open gaps also reports SEALED so the caller keeps re-evaluating, but it is NOT tight). */
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
    /** The hard survival floor: at 2 blocks the body's box (≤1.8 tall) sits entirely below a mob standing on
     *  adjacent ground, and {@code Mob#getAttackBoundingBox} has NO vertical inflation — melee simply cannot
     *  intersect. A 1-deep pit leaves the box overlapping (field death #3: sealed-note then killed by the
     *  zombie villager one block south). */
    private static final int MIN_DEPTH = 2;
    /** Give up descending through the current floor block after this many ticks ONCE the melee-safe depth is
     *  reached (catches hand-mining stone when the extra depth is merely nice-to-have). */
    private static final int DIG_BLOCK_TIMEOUT = 60;
    /** Per-block descent budget while still ABOVE the melee-safe depth: bare-hand stone is 150t and deepening
     *  is the only exit from the attack box, so we grind long before conceding (obsidian-class only). */
    private static final int DIG_BLOCK_TIMEOUT_SHALLOW = 400;
    /** Bounded patch-placement budget per SEAL pass before we accept an exposed hold. */
    private static final int SEAL_MAX_TICKS = 100;
    /** Cadence of the block-measured shell re-audit while holding (cheap: ≤9 getBlockState). */
    private static final int HOLED_AUDIT_PERIOD = 20;

    private final NumenPlayer player;
    private final int targetDepth;
    private final BlockDigger digger;
    private PillarDrive climber;

    private Phase phase = Phase.DIG;
    private int startY = Integer.MIN_VALUE;
    private int lastFeetY = Integer.MIN_VALUE;
    private int digBlockTicks = 0;
    private int sealTicks = 0;
    private int holedTicks = 0;
    private boolean capped = false;
    /** The current floor defeated us (undiggable / too slow) — blocks the SEAL→DIG retry until a descent or
     *  {@link #reverify()} clears it, so the two phases can never ping-pong. */
    private boolean floorHopeless = false;
    /** Block-measured verdict at the last audit: enclosure fully solid AND depth ≥ {@link #MIN_DEPTH}. */
    private boolean sealedTight = false;
    /** Open enclosure cells at the last audit (the honest "缺 N 块" number). */
    private int openGapCount = 0;
    /** The gap set we settled into HOLED with — a gap NOT in this set is a NEW hole and reopens SEAL. */
    private Set<BlockPos> settledGaps = null;

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

    /** True once the drive is holding in the pit (tight or exposed — see {@link #sealedTight()}). */
    public boolean isHoled() {
        return phase == Phase.HOLED;
    }

    /**
     * Force an immediate seal re-verification: HOLED → SEAL with a fresh budget and the floor give-up latch
     * cleared. Call when the body is HIT while holed — a landed hit is empirical proof of a hole (or of a
     * too-shallow pit), so the drive re-audits and patches / deepens instead of crouching in a false shelter.
     * Idempotent; a no-op outside HOLED (DIG/SEAL are already working, BREAKOUT/DONE are past caring).
     */
    public void reverify() {
        if (phase == Phase.HOLED) {
            floorHopeless = false;
            sealTicks = 0;
            settledGaps = null;
            phase = Phase.SEAL;
        }
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
            case HOLED -> tickHoled(level, feet);
            case BREAKOUT -> tickBreakout();
            case DONE -> Status.DONE;
        };
    }

    private Status tickDig(Level level, BlockPos feet) {
        int depth = startY - feet.getY();
        if (feet.getY() < lastFeetY) {           // descended a block → reset the per-block stall timer
            lastFeetY = feet.getY();
            digBlockTicks = 0;
            floorHopeless = false;               // a fresh floor gets a fresh chance
        }
        if (depth >= targetDepth) {              // reached the target depth → seal
            return toSeal();
        }
        BlockPos floor = feet.below();
        if (!player.onGround()) {                // mid-fall after a break — wait to land
            InputDriver.halt(player);
            return Status.WORKING;
        }
        if (!diggableFloor(level, floor)) {      // fluid / bedrock / air-over-void / protected → seal where we are
            floorHopeless = true;
            return toSeal();
        }
        // Below the melee-safe depth the descent is the ONLY thing that removes an adjacent mob's attack box
        // overlap, so it gets the long budget; past it, extra depth is nice-to-have and gives up quickly.
        int budget = depth < MIN_DEPTH ? DIG_BLOCK_TIMEOUT_SHALLOW : DIG_BLOCK_TIMEOUT;
        if (++digBlockTicks > budget) {          // too slow to break (obsidian-class / no pickaxe past depth 2)
            floorHopeless = true;
            return toSeal();
        }
        player.setShiftKeyDown(false);
        digger.dig(floor);                       // native progressive break; body drops into the hole on completion
        return Status.WORKING;
    }

    /** Enter SEAL with a fresh patch budget (the dig is abandoned/cancelled either way). */
    private Status toSeal() {
        digger.cancel();
        sealTicks = 0;
        phase = Phase.SEAL;
        return Status.WORKING;
    }

    /**
     * One SEAL tick. The verdict is block-measured every tick ({@link #sealGaps}) and the action is the pure
     * {@link SealPolicy} decision: hold when provably tight, deepen when depth is the missing ingredient,
     * patch one gap natively otherwise, and settle EXPOSED (honestly) only when neither can proceed.
     */
    private Status tickSeal(Level level, BlockPos feet) {
        InputDriver.halt(player);
        int depth = startY - feet.getY();
        List<BlockPos> gaps = sealGaps(level, feet);
        int slot = sealBlockSlot();
        SealPolicy.Action action = SealPolicy.decide(
                depth, gaps.size(),
                diggableFloor(level, feet.below()), floorHopeless,
                slot >= 0, anyGapUnplaceable(level, gaps), sealTicks < SEAL_MAX_TICKS,
                MIN_DEPTH, targetDepth);
        return switch (action) {
            case HOLD_TIGHT -> settle(level, feet, gaps);       // measured: cap + both rings solid, out of the box
            case DIG_DEEPER -> {
                digBlockTicks = 0;
                phase = Phase.DIG;
                yield Status.WORKING;
            }
            case PLACE -> {
                sealTicks++;
                tryPlaceOne(level, gaps, slot);                 // one native placement attempt per tick, cap first
                yield Status.WORKING;
            }
            case SETTLE_EXPOSED -> settle(level, feet, gaps);   // out of material / geometry / patience — say so
        };
    }

    /**
     * The seal-phase decision table, PURE (no Minecraft types) so it is verifiable on a bare JVM in isolation.
     * Package-private for the differential test harness; the game path only ever calls it via
     * {@link #tickSeal}. Invariants it guarantees (and the harness asserts exhaustively):
     * <ul>
     *   <li><b>No false safety</b>: {@code HOLD_TIGHT} ⇔ zero gaps AND depth ≥ minDepth — the ONLY state from
     *       which a "已封闭" event may be derived.</li>
     *   <li><b>Depth first</b>: below minDepth, a workable floor is always dug before anything else (side
     *       blocks cannot remove the attack-box Y-overlap; only depth can).</li>
     *   <li><b>Never dig the undiggable</b>: {@code DIG_DEEPER} only with a diggable, non-given-up floor,
     *       below targetDepth (so SEAL⇄DIG cannot livelock: depth grows or the floor latch trips).</li>
     *   <li><b>Never idle while fixable</b>: {@code SETTLE_EXPOSED} only when neither digging nor placing
     *       can proceed.</li>
     * </ul>
     */
    static final class SealPolicy {
        enum Action { HOLD_TIGHT, DIG_DEEPER, PLACE, SETTLE_EXPOSED }

        private SealPolicy() {}

        static Action decide(int depth, int gapCount,
                             boolean floorDiggable, boolean floorHopeless,
                             boolean haveMaterial, boolean anyGapUnplaceable, boolean budgetLeft,
                             int minDepth, int targetDepth) {
            if (gapCount == 0 && depth >= minDepth) return Action.HOLD_TIGHT;
            boolean canDig = !floorHopeless && depth < targetDepth && floorDiggable;
            // Deepen when depth is the missing ingredient: still inside the melee box, or patching is
            // impossible from here (no material at all, or a gap no placement geometry can reach — e.g. a
            // cap floating above a shallow pit with no support face).
            if (canDig && (depth < minDepth || !haveMaterial || anyGapUnplaceable)) return Action.DIG_DEEPER;
            if (haveMaterial && budgetLeft && gapCount > 0) return Action.PLACE;
            return Action.SETTLE_EXPOSED;
        }
    }

    private Status tickHoled(Level level, BlockPos feet) {
        InputDriver.halt(player);
        player.setShiftKeyDown(true);            // stay crouched — never step off into the pit wall
        InputDriver.lookAt(player, player.getEyePosition().add(0.0, 2.0, 0.0));   // face the cap (up)
        if (++holedTicks % HOLED_AUDIT_PERIOD == 0) {
            List<BlockPos> gaps = sealGaps(level, feet);
            refreshAudit(level, feet, gaps);
            for (BlockPos gap : gaps) {
                if (settledGaps == null || !settledGaps.contains(gap)) {
                    // A hole that was NOT there when we settled (broken cap / wall, blast) → re-seal now.
                    sealTicks = 0;
                    phase = Phase.SEAL;
                    return Status.WORKING;
                }
            }
        }
        return Status.SEALED;
    }

    /** Settle into HOLED with the audit recorded — tight or honestly exposed, per the measured {@code gaps}. */
    private Status settle(Level level, BlockPos feet, List<BlockPos> gaps) {
        refreshAudit(level, feet, gaps);
        settledGaps = Set.copyOf(gaps);
        phase = Phase.HOLED;
        return Status.SEALED;
    }

    /** Refresh the block-measured shell verdict ({@link #sealedTight}/{@link #openGapCount}/{@link #capped}). */
    private void refreshAudit(Level level, BlockPos feet, List<BlockPos> gaps) {
        capped = isSolidWall(level, feet.above(2));
        openGapCount = gaps.size();
        sealedTight = gaps.isEmpty() && (startY - feet.getY()) >= MIN_DEPTH;
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

    /** Place a seal block into the first gap that is actually placeable this tick (replaceable cell + a
     *  line-of-sight-verified support face), natively: look at it, hold the block, use. Gap order is the
     *  {@link #sealGaps} order — cap first, then the head ring, then the feet ring. */
    private boolean tryPlaceOne(Level level, List<BlockPos> gaps, int slot) {
        for (BlockPos gap : gaps) {
            if (!BlockHelper.isReplaceableForPlacement(level, gap)) continue;   // a partial block sits there
            BlockHitResult hit = Placement.resolve(player, gap, false);
            if (hit == null) continue;               // no reachable support face for THIS gap — try the next
            InputDriver.lookAt(player, Vec3.atCenterOf(gap));
            player.holdInHand(slot);
            Interaction.useBlock(player, hit, InteractionHand.MAIN_HAND).tick();
            return true;
        }
        return false;                                // nothing reachable this tick — retry (budget-bounded)
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

    /** Whether the overhead cap cell is closed (vs. an open crouch) — audited, not assumed. */
    public boolean capped() {
        return capped;
    }

    /**
     * The block-measured seal verdict as of the last audit: cap AND both side rings all full collision cubes
     * AND depth ≥ {@link #MIN_DEPTH} — i.e. no melee mob standing on any adjacent floor has an attack line
     * (no box overlap from above, no line of sight through the shell). This — and ONLY this — justifies a
     * "已封闭掩体" report; a holding drive with {@code sealedTight() == false} is an EXPOSED crouch.
     */
    public boolean sealedTight() {
        return sealedTight;
    }

    /** Open enclosure cells at the last audit — the honest "缺 N 块" number for the not-fully-sealed report. */
    public int openSealGaps() {
        return openGapCount;
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

    /**
     * The enclosure cells that are NOT solid, in patch-priority order: the cap cell above the head first
     * (arrows down the shaft, mobs dropping in), then the four head-level side cells, then the four
     * feet-level ones. Empty ⇒ the shell is physically closed. ≤9 {@code getBlockState} reads.
     */
    private static List<BlockPos> sealGaps(Level level, BlockPos feet) {
        List<BlockPos> gaps = new ArrayList<>(4);
        BlockPos cap = feet.above(2);
        if (!isSolidWall(level, cap)) gaps.add(cap);
        BlockPos head = feet.above();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos side = head.relative(d);
            if (!isSolidWall(level, side)) gaps.add(side);
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(d);
            if (!isSolidWall(level, side)) gaps.add(side);
        }
        return gaps;
    }

    /** A cell that counts as shell: a full collision cube (blocks the stance, the attack line and arrows).
     *  Water, air, slabs, stairs, fences etc. all count as gaps. */
    private static boolean isSolidWall(Level level, BlockPos pos) {
        return level.getBlockState(pos).isCollisionShapeFullBlock(level, pos);
    }

    /** True if some gap can never be closed by placement from here: an occupied non-replaceable cell, or a
     *  cell with no support face at all (placement geometry, no raycast — cheap). Digging deeper changes the
     *  geometry, so this feeds the deepen-over-patch decision. */
    private static boolean anyGapUnplaceable(Level level, List<BlockPos> gaps) {
        for (BlockPos gap : gaps) {
            if (!BlockHelper.isReplaceableForPlacement(level, gap)
                    || !Placement.hasAnySupport(level, gap)) {
                return true;
            }
        }
        return false;
    }

    /** Inventory slot of a placeable full-cube solid block for the shell, or -1 if the pack holds none. */
    private int sealBlockSlot() {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (isSealBlock(inv.getItem(i))) return i;
        }
        return -1;
    }

    /** A {@link BlockItem} whose block forms a full collision cube (so the shell cell actually closes). */
    private static boolean isSealBlock(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi)) return false;
        BlockState def = bi.getBlock().defaultBlockState();
        return Block.isShapeFullBlock(def.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
    }

    private BlockPos feet() {
        return BlockHelper.playerFeet(player.level(), player.getX(), player.getY(), player.getZ());
    }
}
