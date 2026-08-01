package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.exec.BlockDigger;
import com.dwinovo.numen.core.pathing.exec.InputDriver;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.core.pathing.util.BlockScanner;
import com.dwinovo.numen.core.pathing.util.ScanExecutor;
import com.dwinovo.numen.core.pathing.viz.PathVizPublisher;
import com.dwinovo.numen.core.task.CompanionTask;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.core.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * {@code auto_mine} — a faithful port of Baritone's {@code MineProcess} to the
 * companion player body (functionally aligned; not a code copy, since Baritone
 * drives a client LocalPlayer and we drive a server fake player).
 *
 * <h2>The loop (Baritone MineProcess)</h2>
 * <ol>
 *   <li><b>knownOreLocations</b> — periodically rescan the world for target
 *       blocks ({@link BlockScanner}), and {@link #prune} every tick (drop ones
 *       mined / no longer matching / blacklisted / hazardous), sorted by
 *       distance, capped at {@link #MAX_ORES}.</li>
 *   <li><b>shaft</b> — if a target sits in our own column within reach, break it
 *       straight up immediately (no pathing), auto-switching to the best tool —
 *       Baritone's vertical-shaft mining.</li>
 *   <li><b>GoalComposite</b> — otherwise head for the whole ore field at once:
 *       one A* search over {@link NavGoal#composite} of {@link NavGoal#mine}
 *       stances, so it walks to the CLOSEST reachable ore (not greedy-nearest,
 *       which is often the walled-in one).</li>
 *   <li><b>blacklist</b> — when the path search fails, blacklist the nearest ore
 *       (presumed unreachable) and retry — Baritone's blacklistClosestOnFailure.</li>
 *   <li><b>branch mine</b> — when no ore is known, head outward holding the
 *       y-level ({@link NavGoal#runAway}) to dig fresh tunnel and expose more,
 *       bounded by {@link #MAX_BRANCH_TICKS}.</li>
 * </ol>
 */
public final class MineCompanionTask implements CompanionTask {

    private static final int RESCAN_INTERVAL = 10;     // Baritone mineGoalUpdateInterval
    private static final int MAX_ORES = 64;            // Baritone mineMaxOreLocationsCount
    private static final double REACH_SQR = 4.5 * 4.5;
    private static final double MINE_SPEED = 1.0;
    /** Give up branch-mining after this many ticks with no ore found (~30 s). */
    private static final int MAX_BRANCH_TICKS = 600;
    /**
     * Baritone {@code MineProcess.updateGoal}: with {@code exploreForBlocks} (default
     * FALSE) and {@code legitMine} (default FALSE) both off, "no ore known" returns
     * null → the process CANCELS — it does NOT wander off hunting for more. Only with
     * {@code exploreForBlocks} does it branch-mine outward. We mirror the default:
     * stop. Flip this to re-enable Baritone's opt-in explore mode (the branch-mine
     * below). */
    private static final boolean EXPLORE_FOR_BLOCKS = false;
    /** Abandon an in-flight scan after this long so a wedged future can't stop
     *  rescanning forever (scans finish in well under a tick; this only fires if
     *  something is truly stuck). */
    private static final int SCAN_TIMEOUT_TICKS = 200;
    /** Body this many blocks below the heightmap surface + a surface-flora target (logs/leaves)
     *  → the zero-found settlement adds "it only grows on the surface, go back up" guidance. */
    private static final int SURFACE_HINT_MIN_DEPTH = 6;
    /** (A) Consecutive GENUINE "no path" nav failures against one ore before it is
     *  blacklisted — a single transient failure no longer condemns an ore forever
     *  (the diamond 7/8 root cause). Transient causes never accrue a strike. */
    private static final int BLACKLIST_STRIKES = 3;
    /** (A/裁决①) Body displacement (squared) that revives the whole blacklist:
     *  tunnelling changes what's reachable, so "unreachable" from 8+ blocks ago is
     *  stale. The blacklist also clears outright on every successful mine. */
    private static final double BLACKLIST_REVIVE_SQR = 8.0 * 8.0;
    /** (B②/F8) Radius for the per-tick "collect drops as I go" pickup — wider than
     *  the old 5.0 so mined loot a few blocks away isn't abandoned. */
    private static final double DROP_PICKUP_RADIUS = 8.0;
    /** (B③) Cleanup-sweep radius + budget: before declaring the deposit done, spend
     *  up to this many ticks pathing over target drops in a wider radius so a scatter
     *  of dropped ore isn't left on the ground. */
    private static final double SWEEP_RADIUS = 16.0;
    private static final int SWEEP_MAX_TICKS = 200;
    /** (E) Vein-mode safety cap: when {@code vein} is on, {@code count} is only an
     *  upper bound (raised to this so a small count can't truncate a connected vein);
     *  the real stop is vein exhaustion. */
    private static final int VEIN_MIN_CAP = 64;

    private final NumenPlayer player;
    private final MineBlockTaskRecord r;
    private final List<BlockPos> knownOres = new ArrayList<>();
    /** (A) Ores judged genuinely unreachable — excluded from mining, but REVIVED
     *  (cleared) on every successful mine or ≥8-block body move. Never permanent. */
    private final Set<BlockPos> blacklist = new HashSet<>();
    /** (A) Per-ore consecutive genuine-unreachable strike counts; an ore is moved to
     *  {@link #blacklist} only once it reaches {@link #BLACKLIST_STRIKES}. */
    private final Map<BlockPos, Integer> failStrikes = new HashMap<>();
    /** (A) Body position when the blacklist was last cleared — an ≥8-block move revives it. */
    private BlockPos blacklistAnchor;
    /** (B①) TARGET blocks actually broken this run, counted independently of the item
     *  delta so the settlement can distinguish "mined enough but lost M drops"
     *  (physical loss) from "never found/reached the ore". */
    private int brokenCount;
    /** (E) Connected-vein target cells — a 26-adjacency flood-fill over the scan hit
     *  set, seeded from each block broken in vein mode. Empty ⇒ mine normally. */
    private final Set<BlockPos> veinTargets = new HashSet<>();
    /** (B③) Cleanup-sweep tick budget consumed. */
    private int sweepTicks;
    /** True while {@link #nav} is running the wide drop-collection sweep. */
    private boolean navIsSweep;
    /** Items the target blocks drop (loot-table simulated, Baritone BlockOptionalMeta.drops). The
     *  count is over THESE in the inventory, not blocks broken — redstone_ore yields ~4 redstone. */
    private Set<Item> dropItems = Set.of();
    /** Matching items already in the inventory when the task began — the count is the DELTA above this
     *  (companion semantics: "gather N more", not Baritone's absolute "have N"). */
    private int baseline;
    /** Creative-only tally: TARGET blocks actually cleared. Creative gets no natural drops
     *  (preventsBlockDrops), so the item-delta count would sit at 0 forever and every run would false-
     *  FAIL; instead we count real breaks and simulate the drops into the pack (see mineProgress).
     *  This is the progress metric while {@link #isCreative()}. */
    private int creativeBroken;
    /** Nearby dropped items to collect (Baritone droppedItemsScan), refreshed per tick. */
    private List<BlockPos> drops = List.of();

    private PlayerNav nav;
    private boolean navIsBranch;
    private BlockPos branchPoint;
    private int branchY;
    private int rescanTimer;
    private int branchTicks;
    private String doneReason = "done";
    /** In-flight background ore scan (Baritone runs its rescan off the tick thread). */
    private CompletableFuture<List<BlockScanner.Hit>> scan;
    /** Game time by which the in-flight scan must finish or be abandoned. */
    private long scanDeadline;

    // Progressive dig (Baritone mines tick-by-tick, not instabreak) — shared with
    // the path executor so all breaking reads the same.
    private final BlockDigger digger;

    public MineCompanionTask(NumenPlayer player, MineBlockTaskRecord record) {
        this.player = player;
        this.r = record;
        this.digger = new BlockDigger(player);
    }

    @Override
    public void start() {
        // Creative instabreaks any block regardless of tool, so the survival harvest precheck (which
        // fail-fasts when no pickaxe is held) simply doesn't apply — skip it. In survival, fail fast
        // if NO requested target is harvestable with the current inventory — mining it would destroy
        // the block for no drop. Same gate as break_block / the cost model (BlockHelper.canHarvest,
        // whole-inventory). prune() then drops any individual unharvestable cell, so a mixed request
        // (e.g. coal we can mine + diamond we can't) still works.
        if (!isCreative()) {
            boolean anyHarvestable = r.targets.stream().anyMatch(
                    b -> BlockHelper.canHarvest(player.getInventory(), b.defaultBlockState()));
            if (!anyHarvestable) {
                doneReason = "can't harvest " + r.label + " with the current tools — mining it would"
                        + " destroy it without any drop. Equip a suitable tool (e.g. a pickaxe) first.";
                r.setState(TaskState.FAILED);
                return;
            }
        }
        // Count toward `count` by ITEMS gathered (Baritone), not blocks broken: resolve what these
        // blocks drop, and snapshot how many we already hold so the tally is the delta above it.
        // (Creative counts by BLOCKS cleared instead — see tick()/mineProgress — since it gets no
        // natural drops; dropItems/baseline are still resolved for the loot sim that refills the pack.)
        dropItems = computeDropItems();
        baseline = inventoryMatch();
        blacklistAnchor = player.blockPosition();
        rescan();
    }

    @Override
    public TaskState tick() {
        boolean creative = isCreative();
        // Creative gets no natural drops (preventsBlockDrops), so the item-delta would never move —
        // count TARGET blocks actually cleared instead (mineProgress simulates the loot into the pack).
        int gathered = creative ? creativeBroken : Math.max(0, inventoryMatch() - baseline);
        r.setMined(gathered);
        if (gathered >= effectiveCount()) {
            doneReason = creative ? "cleared all requested" : "gathered all requested";
            return TaskState.SUCCESS;
        }

        // (A) Revive the blacklist once the body has tunnelled ≥8 blocks from where it
        //     was last cleared — the geometry that made an ore unreachable has changed.
        maybeReviveBlacklistOnMove();

        // (F2) A full pack means every further break just destroys ore we can't pick up.
        //      Stop before wrecking the deposit (survival only; creative doesn't drop).
        if (!creative && !inventoryHasRoom()) {
            doneReason = "inventory full — stopped so I don't destroy " + r.label
                    + " I can't carry. Free up space (deposit or drop items), then run auto_mine again.";
            return finishByCount();
        }

        Level level = player.level();

        // 0) Continue an in-progress dig, locked onto its block (no re-selection)
        //    until it breaks or drifts out of reach.
        BlockPos digging = digger.current();
        if (digging != null) {
            if (level.getBlockState(digging).isAir() || !withinReach(digging)) {
                digger.cancel();
            } else {
                mineProgress(digging);
                return TaskState.RUNNING;
            }
        }

        // Maintain the ore list: merge a finished background scan, prune every
        // tick (cheap — knownOres is capped at 64), and kick a fresh off-thread
        // scan every RESCAN_INTERVAL ticks (never more than one in flight).
        drainScan();
        prune();
        boolean rescanTick = false;
        if (--rescanTimer <= 0) {
            rescanTimer = RESCAN_INTERVAL;
            rescanTick = true;
            if (scan == null) kickScan();
        }
        drops = creative ? List.of() : droppedItems();   // creative has no natural drops to collect

        // (D) One census line per rescan interval so a recurrence is diagnosable at a glance:
        //     "known>0 active=0" (found it, all on cooldown) vs "active>0 reach=false" (found it,
        //     can't get there) vs "active=0 drops>0" (the drops-only wedge this fix targets).
        if (rescanTick) {
            com.dwinovo.numen.Constants.LOG.info(
                    "[numen-mine] known={} active={} cooling={} drops={} reach={} broke={} gathered={}/{}",
                    knownOres.size(), activeOres().size(), coolingCount(), drops.size(),
                    reachableTarget() != null, brokenCount, r.getMined(), r.count);
        }

        // 1) Mine any target we can already reach + see from here (no pathing) —
        //    a tree gets mined from beside, never by digging under it.
        BlockPos reachable = reachableTarget();
        if (reachable != null) {
            stopNav();
            // Baritone keeps the goal box rendered while it mines in place (the path
            // executor is paused, but drawGoal(behavior.getGoal()) still runs). stopNav
            // cleared the overlay, so re-publish the ore field boxes — otherwise the
            // boxes vanish the instant shaft-mining starts (the "boxes disappear after
            // two logs" bug). No path line while shaft-mining, just the goal.
            PathVizPublisher.publishTargets(player, new ArrayList<>(activeOres()));
            mineProgress(reachable);
            return TaskState.RUNNING;
        }

        // 2) Head for the ore field + nearby drops (GoalComposite), arriving when a
        //    shaft opens up; drops are collected by walking over them (native pickup).
        //    In vein mode, "the ore field" is the committed connected vein (activeOres).
        List<BlockPos> ores = activeOres();
        if (!ores.isEmpty()) {   // (FIX) drops-only 支路不再无预算霸占 step-2;交给下方有界 step-2.5 sweep
            branchTicks = 0;
            sweepTicks = 0;
            if (nav == null || navIsBranch || navIsSweep) {
                stopNav();
                nav = PlayerNav.toGoal(player, this::oreFieldGoal, MINE_SPEED,
                        () -> reachableTarget() != null);
                nav.setHighlights(() -> new ArrayList<>(activeOres()));   // box every active target
                navIsBranch = false;
            }
            switch (nav.tick()) {
                case RUNNING -> { return TaskState.RUNNING; }
                case ARRIVED -> { stopNav(); return TaskState.RUNNING; } // shaft handled next tick
                case FAILED -> {
                    // (A) Only a GENUINE, persistent "no path" strikes an ore, and only after
                    //     BLACKLIST_STRIKES consecutive strikes is it blacklisted. Transient
                    //     causes (over budget / out of scaffolding / replan give-up) don't count.
                    if (!activeOres().isEmpty()) registerNavFailure(nav.failReason());
                    stopNav();
                    return TaskState.RUNNING;
                }
            }
        }

        // 2.5) (B③/F8) Cleanup sweep: we've broken target blocks but haven't collected
        //      enough items (drops fell in a gap / rolled away / an earlier pass missed
        //      them). Before giving up, spend a bounded budget pathing over target drops
        //      in a wider radius so mined loot isn't abandoned on the ground.
        if (!creative && gathered < effectiveCount() && (brokenCount > 0 || !wideTargetDrops().isEmpty())) {
            List<BlockPos> far = wideTargetDrops();
            if (!far.isEmpty() && ++sweepTicks <= SWEEP_MAX_TICKS) {
                if (nav == null || !navIsSweep) {
                    stopNav();
                    nav = PlayerNav.toGoal(player, this::sweepGoal, MINE_SPEED,
                            () -> wideTargetDrops().isEmpty());
                    nav.setHighlights(this::wideTargetDrops);
                    navIsSweep = true;
                }
                switch (nav.tick()) {
                    case RUNNING -> { return TaskState.RUNNING; }
                    case ARRIVED, FAILED -> { stopNav(); return TaskState.RUNNING; }
                }
            }
        }

        // 3) No ore reachable and nothing left to sweep. Baritone's default stops here
        //    (cancel); only its opt-in explore mode branch-mines. Match the default:
        //    finish with an HONEST settlement of what's still in range and why.
        if (!EXPLORE_FOR_BLOCKS) {
            doneReason = terminalMessage();
            return finishByCount();
        }

        // 3b) Opt-in explore (Baritone exploreForBlocks) — branch-mine outward (bounded).
        if (branchPoint == null) {
            branchPoint = player.blockPosition();
            branchY = branchPoint.getY();
        }
        if (++branchTicks > MAX_BRANCH_TICKS) {
            doneReason = terminalMessage();
            return finishByCount();
        }
        if (nav == null || !navIsBranch) {
            stopNav();
            nav = PlayerNav.toGoal(player, () -> NavGoal.runAway(branchPoint, branchY),
                    MINE_SPEED, () -> false);
            nav.setHighlights(() -> new ArrayList<>(activeOres()));   // (empty while branch-exploring)
            navIsBranch = true;
        }
        switch (nav.tick()) {
            case RUNNING, ARRIVED -> { return TaskState.RUNNING; }
            case FAILED -> { stopNav(); return TaskState.RUNNING; } // boxed in — rescan/retry
        }
        return TaskState.RUNNING;
    }

    /** The stop cap for {@code gathered}. Normal mode: the user's item {@code count}.
     *  Vein mode (E): {@code count} is only an upper-bound safety cap (raised to
     *  {@link #VEIN_MIN_CAP} so a small count can't truncate a connected vein) — the
     *  real stop is vein exhaustion (activeOres drains). */
    private int effectiveCount() {
        return r.isVein() ? Math.max(r.count, VEIN_MIN_CAP) : r.count;
    }

    /** (B④) Terminal return honouring the honest settlement: SUCCESS if we gathered
     *  anything OR broke at least {@code count} target blocks (so the ore WAS mined,
     *  only some drops were physically lost) — never an infinite loop, never a false
     *  full-SUCCESS. FAILED only when nothing was mined at all. */
    private TaskState finishByCount() {
        return (r.getMined() > 0 || brokenCount >= r.count) ? TaskState.SUCCESS : TaskState.FAILED;
    }

    // ---- goals ----

    /** GoalComposite over a mining stance per ore, plus a walk-over goal per nearby
     *  drop — one A* search heads for the closest of either. */
    private NavGoal oreFieldGoal() {
        List<BlockPos> ores = activeOres();
        List<NavGoal> goals = new ArrayList<>(ores.size() + drops.size());
        for (BlockPos ore : ores) {
            goals.add(coalesce(ore));
        }
        for (BlockPos drop : drops) {
            goals.add(NavGoal.near(drop, 1.0));   // walk over it; native pickup grabs it
        }
        return goals.isEmpty() ? NavGoal.exact(player.blockPosition()) : NavGoal.composite(goals);
    }

    /** (E) The ore cells we should currently be mining. Normal mode: the whole known
     *  field ({@link #knownOres}). Vein mode once a vein is committed: only the members
     *  of that connected vein still standing (knownOres ∩ {@link #veinTargets}), so the
     *  body mines the hit vein to exhaustion rather than wandering to another deposit.
     *  Returns the live {@code knownOres} list unchanged when vein mode is off/uncommitted,
     *  so the default (non-vein) execution path is byte-for-byte the old behaviour. */
    private List<BlockPos> activeOres() {
        List<BlockPos> base;
        if (r.isVein() && !veinTargets.isEmpty()) {
            base = new ArrayList<>();
            for (BlockPos p : knownOres) {
                if (veinTargets.contains(p)) base.add(p);
            }
        } else {
            base = knownOres;
        }
        // Rotation: hide targets on transient-failure cooldown so the next attempt goes
        // for a different candidate. An empty result routes to the honest step-3 terminal
        // ("N temporarily unreachable — needs scaffolding / another approach") rather than
        // an endless retry of the same unreachable block.
        if (targetCooldown.isEmpty()) return base;
        long now = player.level().getGameTime();
        targetCooldown.values().removeIf(until -> now >= until);
        if (targetCooldown.isEmpty()) return base;
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : base) {
            if (!targetCooldown.containsKey(p)) out.add(p);
        }
        return out;
    }

    /** Targets currently hidden by the transient-failure rotation cooldown (still known,
     *  never condemned) — surfaced in the terminal message so "none reachable right now"
     *  is never reported as "none left". */
    private int coolingCount() {
        if (targetCooldown.isEmpty()) return 0;
        int n = 0;
        for (BlockPos p : knownOres) {
            if (targetCooldown.containsKey(p)) n++;
        }
        return n;
    }

    /** Nearby target-drop item entities within {@code radius} (Baritone droppedItemsScan,
     *  FILTERED to the target's own drops — F8 — so the body never detours for a
     *  skeleton's bones or fills its pack with junk). Walking over them lets native
     *  pickup collect them. */
    private List<BlockPos> targetDropsWithin(double radius) {
        AABB box = new AABB(player.blockPosition()).inflate(radius);
        List<BlockPos> out = new ArrayList<>();
        for (ItemEntity ie : player.level().getEntitiesOfClass(ItemEntity.class, box)) {
            if (dropItems.contains(ie.getItem().getItem())) out.add(ie.blockPosition());
        }
        return out;
    }

    /** (B③) Wider-radius scan of target drops, used only by the terminal cleanup sweep. */
    private List<BlockPos> wideTargetDrops() {
        return targetDropsWithin(SWEEP_RADIUS);
    }

    /** (B③) Walk-over goal composite for the cleanup sweep. */
    private NavGoal sweepGoal() {
        List<BlockPos> far = wideTargetDrops();
        if (far.isEmpty()) return NavGoal.exact(player.blockPosition());
        List<NavGoal> goals = new ArrayList<>(far.size());
        for (BlockPos d : far) goals.add(NavGoal.near(d, 1.0));
        return NavGoal.composite(goals);
    }

    /**
     * Baritone {@code MineProcess.coalesce} with {@code forceInternalMining=true}
     * (its DEFAULT) — pick the mining-stance goal for one ore so the body never
     * stands BELOW the bottom of a vein/trunk. The blind {@code GoalThreeBlocks}
     * (feet up to two below every ore) was our divergence: it made a tree's
     * bottom log's −2 cell a valid stance, and bare-handed (logs dear, dirt
     * cheap) A* dug under to it. Baritone instead asks "is the block above / below
     * this one ALSO something I'm mining?": the bottom of a vertical run (target
     * above, plain ground below) gets {@code GoalBlock} — feet EXACTLY at the ore,
     * mined where you stand, never dug under.
     */
    private NavGoal coalesce(BlockPos loc) {
        boolean assumeVerticalShaftMine =
                !(player.level().getBlockState(loc.above()).getBlock()
                        instanceof net.minecraft.world.level.block.FallingBlock);
        boolean upwardGoal = internalMiningGoal(loc.above());
        boolean downwardGoal = internalMiningGoal(loc.below());
        boolean doubleDownwardGoal = internalMiningGoal(loc.below(2));
        if (upwardGoal == downwardGoal) {                       // symmetric vertically
            return (doubleDownwardGoal && assumeVerticalShaftMine)
                    ? NavGoal.mineColumn(loc, 2)                // GoalThreeBlocks
                    : NavGoal.mineColumn(loc, 1);              // GoalTwoBlocks
        }
        if (upwardGoal) {                                       // bottom of a run: stand in it
            return NavGoal.mineColumn(loc, 0);                 // GoalBlock — feet exactly here
        }
        return (doubleDownwardGoal && assumeVerticalShaftMine) // top of a run, more below
                ? NavGoal.mineColumn(loc.below(), 1)           // GoalTwoBlocks(below)
                : NavGoal.mineColumn(loc.below(), 0);          // GoalBlock(below)
    }

    /**
     * Baritone {@code MineProcess.internalMiningGoal}: is {@code pos} also part of
     * what we're mining — a known target, a filter match, or (the air exception,
     * default on) already-broken air continuing the shaft? Used by {@link #coalesce}
     * to read the run a block sits in.
     */
    private boolean internalMiningGoal(BlockPos pos) {
        if (knownOres.contains(pos)) return true;
        net.minecraft.world.level.block.state.BlockState state = player.level().getBlockState(pos);
        if (state.isAir()) return true;                         // internalMiningAirException
        return r.targets.contains(state.getBlock());
    }

    /** Nearby target drops to collect as we mine (Baritone droppedItemsScan). Radius
     *  widened from 5.0 to {@link #DROP_PICKUP_RADIUS} (B②) so mined loot a few blocks
     *  off isn't left behind, and filtered to the target's own drops (F8) so the body
     *  doesn't detour for unrelated items. Walking over them lets native pickup grab them. */
    private List<BlockPos> droppedItems() {
        return targetDropsWithin(DROP_PICKUP_RADIUS);
    }

    /**
     * Baritone's MineProcess "shaft" — EXACT port: a known target in the body's
     * OWN feet column (x/z match), at or above feet, still solid, and reachable
     * (within reach + clear sight = {@code RotationUtils.reachable}). Mined in
     * place, no pathing. The A* (GoalThreeBlocks) is what gets the body INTO the
     * column; this only fires once it's there. No reach-from-the-side shortcut.
     */
    private BlockPos reachableTarget() {
        if (!player.onGround()) return null;
        Level level = player.level();
        BlockPos feet = player.blockPosition();
        Vec3 eyes = player.getEyePosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos ore : activeOres()) {
            if (ore.getX() != feet.getX() || ore.getZ() != feet.getZ()) continue;   // same column
            if (ore.getY() < feet.getY()) continue;                                  // at or above feet
            if (level.getBlockState(ore).isAir()) continue;
            if (!withinReach(ore) || !hasLineOfSight(eyes, ore)) continue;           // reachable
            double d = ore.distSqr(feet.above());
            if (d < bestD) {
                bestD = d;
                best = ore;
            }
        }
        return best;
    }

    /** Clear sight line from the eyes to the target block's centre (nothing solid
     *  blocks it but the target itself). */
    private boolean hasLineOfSight(Vec3 eyes, BlockPos target) {
        BlockHitResult hit = player.level().clip(new ClipContext(
                eyes, Vec3.atCenterOf(target),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    // ---- mining (progressive, tick-by-tick like Baritone / a real player) ----

    /** Advance the shared dig one tick (it switches to the best tool itself); on the tick it breaks,
     *  drop it from the ore list. The progress count is read from the inventory each tick, not here —
     *  one block can yield several items, and the drops take a moment to be picked up. */
    private void mineProgress(BlockPos pos) {
        // Capture the block state BEFORE the dig: creative needs it to simulate the loot the break
        // won't naturally drop, and vein mode needs its block to grow the connected vein. digger.dig
        // returns true ONLY when the TARGET itself breaks (not an occluder cleared to reach it).
        BlockState before = player.level().getBlockState(pos);
        boolean creative = isCreative();
        if (digger.dig(pos)) {
            knownOres.remove(pos);
            brokenCount++;                          // (B①) independent broken-target tally
            onOreMined(pos, before.getBlock());     // (A) revive blacklist; (E) grow the vein
            if (creative) {
                creativeBroken++;
                simulateDropsToInventory(before, pos);
            }
        }
    }

    /** A target block just broke: (A) the terrain changed, so wipe the "unreachable"
     *  memory — newly exposed ore deserves a fresh try; (E) extend the committed vein
     *  across the block we broke. */
    private void onOreMined(BlockPos pos, Block minedBlock) {
        blacklist.clear();
        failStrikes.clear();
        blacklistAnchor = player.blockPosition();
        if (r.isVein()) growVein(pos, minedBlock);
    }

    /** Creative-only: put the loot {@code brokenState} WOULD have dropped straight into the pack.
     *  Creative breaking sets preventsBlockDrops (zero natural drops), so we roll the same loot table
     *  a survival break would (with the best harvesting tool we carry — Fortune/Silk Touch respected),
     *  via the exact {@code Block.getDrops} overload {@link #computeDropItems} uses, and
     *  {@link Inventory#add} each stack. Only the requested TARGET blocks refill the pack (an occluder
     *  cleared to open line of sight doesn't). */
    private void simulateDropsToInventory(BlockState brokenState, BlockPos pos) {
        if (brokenState == null || brokenState.isAir()) return;
        if (!r.targets.contains(brokenState.getBlock())) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        List<ItemStack> loot;
        try {
            loot = Block.getDrops(brokenState, level, pos, null, player, bestToolFor(brokenState));
        } catch (RuntimeException broken) {
            loot = List.of();
        }
        Inventory inv = player.getInventory();
        for (ItemStack stack : loot) {
            if (!stack.isEmpty()) inv.add(stack);
        }
    }

    // ---- item counting (Baritone MineProcess: count matching items in the inventory) ----

    /** Matching items currently in the inventory (sum of stack counts whose item the targets drop). */
    private int inventoryMatch() {
        if (dropItems.isEmpty()) return baseline;   // before start() resolved the set — no progress yet
        Inventory inv = player.getInventory();
        int sum = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && dropItems.contains(s.getItem())) sum += s.getCount();
        }
        return sum;
    }

    /** The item set the target blocks drop — Baritone {@code BlockOptionalMeta.drops}, via the server
     *  loot table rolled once per target with the best harvesting tool we carry (so an ore yields its
     *  ingot/gem, stone yields cobblestone, etc.). Falls back to the block's own item if it has no loot. */
    private Set<Item> computeDropItems() {
        Set<Item> items = new HashSet<>();
        if (!(player.level() instanceof ServerLevel level)) {
            for (Block b : r.targets) items.add(b.asItem());
            return items;
        }
        BlockPos origin = player.blockPosition();
        for (Block b : r.targets) {
            BlockState state = b.defaultBlockState();
            List<ItemStack> drops;
            try {
                drops = Block.getDrops(state, level, origin, null, player, bestToolFor(state));
            } catch (RuntimeException broken) {
                drops = List.of();
            }
            if (drops.isEmpty()) {
                items.add(b.asItem());
            } else {
                for (ItemStack d : drops) items.add(d.getItem());
            }
        }
        return items;
    }

    /** The inventory item that mines {@code state} fastest — the tool the dig will actually use, so the
     *  simulated drops match the real ones (e.g. respects a Silk Touch / Fortune pick if carried). */
    private ItemStack bestToolFor(BlockState state) {
        Inventory inv = player.getInventory();
        ItemStack best = inv.getItem(inv.getSelectedSlot());
        float bestSpeed = best.getDestroySpeed(state);
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            float speed = s.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = s;
            }
        }
        return best;
    }

    // ---- ore list maintenance ----

    /** Synchronous scan — used once at task start so there are targets immediately. */
    private void rescan() {
        mergeHits(BlockScanner.findWithin(
                player.level(), player.blockPosition(), r.maxRadius, r.targets));
    }

    /** Kick an off-thread scan: capture the loaded chunks on this (main) thread,
     *  read their section palettes on the scan thread (Baritone's WorldScanner model). */
    private void kickScan() {
        Level level = player.level();
        BlockPos center = player.blockPosition().immutable();
        List<ChunkAccess> chunks = BlockScanner.captureLoadedChunks(level, center, r.maxRadius);
        if (chunks.isEmpty()) return;
        scan = ScanExecutor.submit(
                () -> BlockScanner.scanLoaded(level, chunks, center, r.maxRadius, r.targets));
        scanDeadline = level.getGameTime() + SCAN_TIMEOUT_TICKS;
    }

    /** Merge a finished background scan into knownOres on the main thread. */
    private void drainScan() {
        if (scan == null) return;
        if (!scan.isDone()) {
            if (player.level().getGameTime() > scanDeadline) {   // wedged — drop it, re-kick later
                scan.cancel(false);
                scan = null;
            }
            return;
        }
        List<BlockScanner.Hit> hits;
        try {
            hits = scan.getNow(List.of());
        } catch (Throwable failed) {
            hits = List.of();
        }
        scan = null;
        mergeHits(hits);
    }

    /** Add fresh, non-blacklisted, non-hazardous hits to knownOres, then prune.
     *  Every candidate is re-validated here on the main thread, so a slightly
     *  stale async scan result is harmless. */
    private void mergeHits(List<BlockScanner.Hit> hits) {
        Level level = player.level();
        for (BlockScanner.Hit hit : hits) {
            BlockPos p = hit.pos().immutable();
            if (blacklist.contains(p) || knownOres.contains(p)) continue;
            if (BlockMiningProgress.fluidBreakHazard(level, p) != null) continue;
            knownOres.add(p);
        }
        prune();
    }

    private void prune() {
        Level level = player.level();
        BlockPos feet = player.blockPosition();
        boolean creative = isCreative();   // creative breaks anything: don't drop targets on the tool gate
        knownOres.removeIf(p -> {
            BlockState s = level.getBlockState(p);
            return s.isAir()
                    || !r.targets.contains(s.getBlock())
                    || blacklist.contains(p)
                    || BlockMiningProgress.fluidBreakHazard(level, p) != null
                    // (F3) sand/gravel column sitting directly on the ore — shaft-mining it
                    // would drop the falling column into the dig and onto the body's head.
                    || BlockHelper.breakReleasesFallingBlock(level, p)
                    || (!creative && !BlockHelper.canHarvest(player.getInventory(), s));
        });
        knownOres.sort(Comparator.comparingDouble(feet::distSqr));
        if (knownOres.size() > MAX_ORES) {
            knownOres.subList(MAX_ORES, knownOres.size()).clear();
        }
    }

    /** Transient nav failures put the failed target on this short rotation cooldown so the
     *  next attempt tries a DIFFERENT candidate instead of hammering the same one. Field
     *  case: bare-hand tree chopping — the canopy logs need scaffolding the body doesn't
     *  carry, so "transient, retry later" looped forever on one log while other trees
     *  stood in range. Cooling targets stay known (never condemned) and re-enter the
     *  rotation when the cooldown lapses, the body mines something, or it relocates. */
    private final Map<BlockPos, Long> targetCooldown = new HashMap<>();
    private static final int TRANSIENT_COOLDOWN_TICKS = 300;   // 15 s

    /** (A) A composite nav search failed to reach the whole active field. ALWAYS cool the
     *  nearest active ore (rotate to another candidate next attempt); additionally strike
     *  it toward the blacklist ONLY for a genuine persistent cause. */
    private void registerNavFailure(String failReason) {
        BlockPos feet = player.blockPosition();
        BlockPos nearest = activeOres().stream()
                .min(Comparator.comparingDouble(feet::distSqr))
                .orElse(null);
        if (nearest == null) return;
        targetCooldown.put(nearest.immutable(),
                player.level().getGameTime() + TRANSIENT_COOLDOWN_TICKS);
        if (!isGenuineUnreachable(failReason)) return;   // transient — rotated, never condemned
        int strikes = failStrikes.merge(nearest, 1, Integer::sum);
        if (strikes >= BLACKLIST_STRIKES) {
            blacklist.add(nearest);
            failStrikes.remove(nearest);
            knownOres.remove(nearest);
            veinTargets.remove(nearest);
        }
    }

    /**
     * (A③) Does {@link PlayerNav#failReason()} name a GENUINE, persistent "no way
     * there" — walls / broken terrain / a hard break-veto on the line — as opposed to
     * a TRANSIENT cause that mining or moving would resolve? Only genuine failures
     * accrue a blacklist strike; over-budget searches and out-of-scaffolding stalls
     * are retried. Matches the wording {@code PlayerNav.describeEmptyPath} emits — this
     * only CONSUMES the string; PlayerNav is owned by another refactor wave and is not
     * touched here.
     */
    private static boolean isGenuineUnreachable(String reason) {
        if (reason == null) return false;
        return !(reason.contains("search budget")      // over-budget A* — move nearer and retry
                || reason.contains("no scaffolding")    // out of bridging blocks — carry cobblestone
                || reason.contains("replans")           // gave up after N replans — transient wedge
                || reason.contains("target lost"));     // goal supplier blipped null — transient
    }

    /** (A/裁决①) Clear the blacklist once the body has moved ≥8 blocks from where it was
     *  last cleared — tunnelling changes reachability, so stale "unreachable" verdicts
     *  shouldn't outlive the geometry that caused them. */
    private void maybeReviveBlacklistOnMove() {
        BlockPos feet = player.blockPosition();
        if (blacklistAnchor == null || (blacklist.isEmpty() && failStrikes.isEmpty())) {
            blacklistAnchor = feet;   // nothing to revive; keep the anchor at the current spot
            return;
        }
        if (feet.distSqr(blacklistAnchor) >= BLACKLIST_REVIVE_SQR) {
            blacklist.clear();
            failStrikes.clear();
            blacklistAnchor = feet;
        }
    }

    /** (F2) Survival only: is there room to store at least one more of the target's
     *  drops — an empty slot, or a not-yet-full stack of a drop item? A full pack means
     *  mining just destroys ore we can't carry. */
    private boolean inventoryHasRoom() {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) return true;
            if (dropItems.contains(s.getItem()) && s.getCount() < s.getMaxStackSize()) return true;
        }
        return false;
    }

    // ---- vein mode (E): flood-fill the connected same-family ore off each broken block ----

    /** (E) Grow {@link #veinTargets} by a 26-neighbourhood flood-fill over the current
     *  scan hit set ({@link #knownOres}), seeded at the block we just broke, following
     *  connected same-family ore (deepslate/stone variants count as one family). As each
     *  vein member is later mined this re-runs from it, so the committed vein extends to
     *  distal cells the scan only reveals once we've opened the rock. */
    private void growVein(BlockPos seed, Block seedBlock) {
        Set<BlockPos> known = new HashSet<>(knownOres);
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed);
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos n = p.offset(dx, dy, dz);
                        if (veinTargets.contains(n) || !known.contains(n)) continue;
                        Block nb = player.level().getBlockState(n).getBlock();
                        if (!sameVeinFamily(seedBlock, nb)) continue;
                        veinTargets.add(n);
                        queue.add(n);
                    }
                }
            }
        }
    }

    /** (E) Same ore family for vein connectivity: identical blocks, or a stone/deepslate
     *  variant pair (deepslate_diamond_ore ≡ diamond_ore). */
    private static boolean sameVeinFamily(Block a, Block b) {
        if (a == b) return true;
        String pa = BuiltInRegistries.BLOCK.getKey(a).getPath();
        String pb = BuiltInRegistries.BLOCK.getKey(b).getPath();
        return normalizeVariant(pa).equals(normalizeVariant(pb));
    }

    private static String normalizeVariant(String path) {
        return path.startsWith("deepslate_") ? path.substring("deepslate_".length()) : path;
    }

    // ---- honest terminal settlement (A④/B④/D/F3/F6) ----

    /** A current census of target blocks still standing in range, bucketed by WHY each
     *  wasn't gathered, plus how many chunks in range were unloaded/unsearched. */
    private record Remaining(int blacklisted, int falling, int toolBlocked, int fluid,
                             int reachable, int unloadedChunks) {}

    /** Fresh one-shot survey (task-end only) of what remains and why — drives the honest
     *  terminal message instead of the old flat "no more in range" lie. */
    private Remaining summarizeRemaining() {
        Level level = player.level();
        boolean creative = isCreative();
        int bl = 0, fall = 0, tool = 0, fluid = 0, reach = 0;
        for (BlockScanner.Hit hit : BlockScanner.findWithin(
                level, player.blockPosition(), r.maxRadius, r.targets)) {
            BlockPos p = hit.pos();
            BlockState s = level.getBlockState(p);
            if (s.isAir() || !r.targets.contains(s.getBlock())) continue;
            if (BlockMiningProgress.fluidBreakHazard(level, p) != null) { fluid++; continue; }
            if (BlockHelper.breakReleasesFallingBlock(level, p)) { fall++; continue; }
            if (!creative && !BlockHelper.canHarvest(player.getInventory(), s)) { tool++; continue; }
            if (blacklist.contains(p)) { bl++; continue; }
            reach++;
        }
        int unloaded = BlockScanner.countUnloadedChunks(level, player.blockPosition(), r.maxRadius);
        return new Remaining(bl, fall, tool, fluid, reach, unloaded);
    }

    /**
     * (A④/B④/D/F3/F6) Build the honest terminal reason. Never claims "no more in range"
     * while ore is still standing but unreachable / hazardous / un-harvestable, and never
     * hides a physical drop loss behind a bare item count.
     */
    private String terminalMessage() {
        Remaining rem = summarizeRemaining();
        int mined = r.getMined();
        boolean minedEnough = brokenCount >= r.count;
        StringBuilder sb = new StringBuilder();
        if (mined > 0 || minedEnough) {
            sb.append("gathered ").append(mined).append('/').append(r.count).append(' ').append(r.label);
            if (minedEnough && mined < r.count) {
                sb.append(" — broke ").append(brokenCount).append(" block(s) but ")
                  .append(r.count - mined).append(" drop(s) were lost (fell in a gap or despawned)");
            }
        } else {
            sb.append("no ").append(r.label).append(" gathered");
        }
        List<String> notes = new ArrayList<>();
        int cooling = coolingCount();
        if (cooling > 0) {
            notes.add(cooling + " couldn't be reached on this attempt (too high without scaffolding "
                    + "blocks, or the route failed) — carry dirt/cobblestone to pillar up, use pillar_up, "
                    + "or approach from another side and rerun");
        }
        if (rem.blacklisted() > 0) {
            notes.add(rem.blacklisted() + " still in range but currently unreachable (walled off / no path"
                    + ") — place cobblestone stepping-stones or approach from another side, then retry");
        }
        if (rem.falling() > 0) {
            notes.add(rem.falling() + " skipped: sand/gravel sits directly above (mining would drop the "
                    + "falling column into the dig)");
        }
        if (rem.toolBlocked() > 0) {
            notes.add(rem.toolBlocked() + " left unmined: no tool in the pack can harvest them — the right "
                    + "pickaxe broke or is missing; equip one and rerun");
        }
        if (rem.fluid() > 0) {
            notes.add(rem.fluid() + " skipped: lava/water next to them would flood the dig");
        }
        if (rem.unloadedChunks() > 0) {
            notes.add(rem.unloadedChunks() + " chunk(s) in range aren't loaded and weren't searched — "
                    + "move closer to check them");
        }
        if (rem.reachable() > 0) {
            notes.add(rem.reachable() + (r.isVein()
                    ? " more in other veins nearby — this run mined the connected vein you hit; rerun to take them"
                    : " still reachable in range but the run ended before reaching them"));
        }
        if (notes.isEmpty()) {
            sb.append(mined > 0 || minedEnough
                    ? ", no more " + r.label + " in range"
                    : " — none found within " + r.maxRadius + " blocks");
            // Environment fact the brain can't infer from a bare "none found": surface flora does
            // not generate underground, so a cave search for logs is structurally hopeless — walking
            // 40 blocks and rerunning just repeats the miss (owner field report: Fenn hunted trees
            // in a mine shaft). Say WHERE the target actually lives.
            if (mined == 0 && !minedEnough && surfaceFloraTarget()) {
                int surfaceY = player.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        player.getBlockX(), player.getBlockZ());
                int depth = surfaceY - player.getBlockY();
                if (depth >= SURFACE_HINT_MIN_DEPTH) {
                    sb.append(". You are underground (y=").append(player.getBlockY())
                      .append(", surface ≈ y=").append(surfaceY).append(") and ").append(r.label)
                      .append(" only grows on the surface — get back up first (escape_to_surface), then rerun there");
                }
            }
        } else {
            sb.append(". Remaining: ").append(String.join("; ", notes)).append('.');
        }
        return sb.toString();
    }

    /** Every target block is surface flora (logs / leaves / saplings) — stuff that does not generate
     *  underground, so a cave search for it is structurally hopeless rather than merely unlucky. */
    private boolean surfaceFloraTarget() {
        if (r.targets.isEmpty()) return false;
        return r.targets.stream().allMatch(b -> {
            BlockState s = b.defaultBlockState();
            return s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(BlockTags.SAPLINGS);
        });
    }

    private boolean withinReach(BlockPos pos) {
        return player.distanceToSqr(Vec3.atCenterOf(pos)) <= REACH_SQR;
    }

    /** The body is in creative mode (instabuild): instabreaks any block, needs no tool, and gets no
     *  natural block drops (preventsBlockDrops). Drives every creative branch in this task. */
    private boolean isCreative() {
        return player.getAbilities().instabuild;
    }

    private void stopNav() {
        if (nav != null) {
            nav.stop();
            nav = null;
        }
        navIsBranch = false;
        navIsSweep = false;
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        stopNav();
        // stopNav only clears the overlay when a nav exists; if we finished while
        // shaft-mining (nav == null) the goal boxes would otherwise linger, so clear
        // explicitly. Idempotent with stopNav's own clear.
        PathVizPublisher.clear(player);
        digger.cancel();
        if (scan != null) {
            scan.cancel(false);
            scan = null;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("target", r.label);
        data.put("requested", r.count);
        data.put("gathered", r.getMined());
        boolean creative = isCreative();
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(
                    creative
                        ? "creative mode: cleared " + r.getMined() + " " + r.label
                            + " block(s) and placed the drops in the inventory"
                        : "gathered " + r.getMined() + "/" + r.count + " " + r.label + " (" + doneReason + ")",
                    data);
            case TIMEOUT -> new TaskResult(false,
                    "timed out after gathering " + r.getMined() + "/" + r.count + " " + r.label, true, false, data);
            case CANCELLED -> new TaskResult(false,
                    "interrupted after gathering " + r.getMined() + "/" + r.count + " " + r.label, false, true, data);
            case FAILED -> TaskResult.fail(doneReason, data);
            default -> TaskResult.fail("unexpected state: " + finalState, data);
        };
    }

    /** 结算时刻的实时进度(契约方法):已采几件、破坏几块。start() 早退时计数为零,安全。 */
    @Override
    public String progressSummary() {
        return "已采 " + r.getMined() + "/" + r.count + " " + r.label + "(破坏 " + brokenCount + " 块)";
    }
}
