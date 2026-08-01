package com.dwinovo.numen.core.perception.region;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.pathing.util.BlockScanner;
import com.dwinovo.numen.entity.CompanionLifecycle;
import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ④ 周边视觉 (valuables sighting sweep) — the L1 spinal reflex that gives a companion an
 * <em>instant, passing-by</em> awareness of what is worth remembering nearby, appended the
 * moment a world-action task settles.
 *
 * <h2>Why it exists (the field case)</h2>
 * {@code auto_mine(diamond, count=8)} stops the instant it has 8, even when 2 more diamonds sit
 * one block deeper in the same wall — the tool obeys its count and only mines loaded chunks. The
 * brain then has no record those 2 exist. This sweep is the missing eye: when the body settles a
 * task next to that vein it emits "路过时留意到:钻石矿×2 @ (x,y,z)", so the brain can come back.
 *
 * <h2>Where it fires — the same mechanical choke, no new tick hook</h2>
 * Called from {@link MoveSettlement#onTaskSettled} (which the pack-side task-result seam already
 * invokes for every settled server task). No per-tick poll, no per-tool opt-in — one hook covers
 * move_to / auto_mine / break_block / … alike ("放在区域 tool 结算时"). Self-guarded: a sweep miss
 * never destabilises the task-result path.
 *
 * <h2>Append-only, deduped, zero-append</h2>
 * <ul>
 *   <li><b>Dedup</b> — each companion keeps two bounded "already reported" sets ({@link Memory}):
 *       blocks keyed by packed {@link BlockPos}+block-type, entities by entity id. Only genuinely
 *       NEW sightings enter an event; a re-scan of the same spot appends nothing.</li>
 *   <li><b>Staleness</b> — a mined-out vein simply stops being scanned (its blocks are gone, so it
 *       is never re-recorded); the seen set is an LRU capped at {@link #SEEN_CAP}, so the stalest
 *       entries age out and infinite growth is impossible. Cleared when the body leaves the world
 *       (self-registered {@link CompanionLifecycle#onRemove}).</li>
 *   <li><b>Zero-append</b> — a sweep that finds nothing new emits NOTHING; a sweep that finds new
 *       valuables emits exactly ONE summary event (not one per block).</li>
 * </ul>
 *
 * <h2>Complementary to L2 regional observation (not a duplicate)</h2>
 * {@link RegionObservation} produces a region-hashed snapshot ("显著方块:iron_ore×N") only when the
 * region hash changes. This sweep is the immediate first-person view: it fires at settlement time
 * (not on a hash change), it includes <em>entities</em> (villagers, drops, named mobs, chest carts…),
 * and it is deduped independently so a passed vein is reported once, never as a noise flood.
 *
 * <h2>Thread context</h2>
 * <strong>Server thread only</strong> (the task-result drain runs there); the memory map is
 * concurrent for defensive clarity, matching {@code RegionStore.STORES} / {@link MoveSettlement}.
 */
public final class ValuablesSweep {

    /**
     * Spherical scan radius in blocks. 16 is affordable here because {@link BlockScanner#findWithin}
     * short-circuits on each chunk section's palette — sections that hold no whitelisted block are
     * skipped without touching their 4096 cells, and valuables (ore / chests / spawners) are sparse,
     * so the cost is a handful of chunk lookups, not the ~17k naive {@code getBlockState} calls a
     * radius-16 sphere would otherwise imply. Tunable.
     */
    static final int RADIUS = 16;

    /** Max distinct block groups reported in one event (highest value / nearest first). */
    static final int BLOCK_TYPE_CAP = 10;
    /** Max distinct entity groups reported in one event. */
    static final int ENTITY_CAP = 6;

    /** Per-companion "already reported" soft cap — LRU eviction of the stalest entries beyond this. */
    static final int SEEN_CAP = 512;

    /** Per-companion dedup memory, keyed by body UUID. Server-thread only; concurrent for clarity. */
    private static final Map<UUID, Memory> MEMORIES = new ConcurrentHashMap<>();

    /** The valuable-block set, built once from the registry on first use (registry is fully populated by then). */
    private static volatile Set<Block> valuableBlocksCache;

    private static volatile boolean cleanupRegistered;

    private ValuablesSweep() {}

    // ================================================================= entry

    /**
     * Sweep the body's surroundings once, at a task settlement point. Emits a single deduped sighting
     * event when — and only when — something new and valuable is nearby. Never throws into the caller.
     */
    public static void onSettled(NumenPlayer body) {
        ensureCleanup();
        try {
            if (!(body.level() instanceof ServerLevel level)) return;
            Memory mem = MEMORIES.computeIfAbsent(body.getUUID(), k -> new Memory());
            BlockPos center = body.blockPosition();

            List<Sighting> blocks = scanBlocks(level, center, mem);
            List<Sighting> entities = scanEntities(level, body, mem);
            if (blocks.isEmpty() && entities.isEmpty()) return;   // zero-append: nothing new nearby

            String note = formatSighting(blocks, entities);
            Companions.emitEvent(body, "<event kind=\"sighting\">" + note + "</event>", false);
        } catch (Throwable t) {
            Constants.LOG.error("[numen-core] valuables sweep failed for {}", body.getUUID(), t);
        }
    }

    /** Drop a companion's dedup memory when its body leaves the world. */
    public static void drop(UUID companionUuid) {
        MEMORIES.remove(companionUuid);
    }

    // ============================================================== block scan

    private static List<Sighting> scanBlocks(ServerLevel level, BlockPos center, Memory mem) {
        List<BlockScanner.Hit> hits = BlockScanner.findWithin(level, center, RADIUS, valuableBlocks());
        if (hits.isEmpty()) return List.of();
        // Group NEW (unseen) hits by display name; nearest representative + count per group.
        LinkedHashMap<String, Group> groups = new LinkedHashMap<>();
        for (BlockScanner.Hit hit : hits) {
            Block block = hit.state().getBlock();
            long key = blockKey(hit.pos(), block);
            if (mem.blocks.contains(key)) continue;             // reported earlier — dedup skip
            String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
            String label = safe(block.getName().getString());
            groups.computeIfAbsent(label, l -> new Group())
                    .observe(key, blockValueScore(path), hit.distance(), hit.pos());
        }
        return reportGroups(groups, BLOCK_TYPE_CAP, mem.blocks);
    }

    // ============================================================= entity scan

    private static List<Sighting> scanEntities(ServerLevel level, NumenPlayer body, Memory mem) {
        AABB box = body.getBoundingBox().inflate(RADIUS);
        List<Entity> near = level.getEntities(body, box);       // `body` is excluded by the getter
        if (near.isEmpty()) return List.of();
        LinkedHashMap<String, Group> groups = new LinkedHashMap<>();
        for (Entity e : near) {
            if (e instanceof NumenPlayer) continue;             // a sibling companion is not a "valuable"
            double d = body.distanceTo(e);
            if (d > RADIUS) continue;                           // clip the cube back to the scan sphere
            EntDesc desc = describeEntity(e);
            if (desc == null) continue;                         // not a valuable entity
            long key = e.getId();
            if (mem.entities.contains(key)) continue;           // reported earlier — dedup skip
            groups.computeIfAbsent(desc.label(), l -> new Group())
                    .observe(key, desc.value(), d, e.blockPosition());
        }
        return reportGroups(groups, ENTITY_CAP, mem.entities);
    }

    /**
     * Classify an entity as a valuable sighting, or {@code null} if it is not worth a note. Specific
     * value categories win first; any OTHER entity is reported only when it carries a custom name
     * (so ordinary mobs never spam the log). "packs 可扩展": add branches for new valuable entity types.
     */
    private static EntDesc describeEntity(Entity e) {
        if (e instanceof ItemEntity item) {
            ItemStack st = item.getItem();
            if (st.isEmpty()) return null;
            return new EntDesc("掉落物 " + safe(st.getHoverName().getString()) + "×" + st.getCount(), 60);
        }
        if (e instanceof Villager) return new EntDesc("村民", 70);
        if (e instanceof WanderingTrader) return new EntDesc("流浪商人", 72);
        if (e instanceof AbstractMinecartContainer) return new EntDesc("储物矿车", 65);
        if (e instanceof AbstractChestBoat) return new EntDesc("箱船", 65);
        if (e instanceof ItemFrame frame) {
            if (frame.getItem().isEmpty()) return null;
            return new EntDesc("展示框(" + safe(frame.getItem().getHoverName().getString()) + ")", 55);
        }
        if (e instanceof Allay) return new EntDesc("悦灵", 50);
        if (e instanceof Player p) return new EntDesc("玩家 " + safe(p.getName().getString()), 80);
        if (e.hasCustomName()) return new EntDesc("命名生物「" + safe(e.getCustomName().getString()) + "」", 75);
        return null;
    }

    // ===================================================== rank / cap / commit

    /**
     * Rank the grouped sightings (value desc, nearest-distance asc, label asc for determinism), keep
     * the top {@code cap}, and COMMIT only the reported groups' member keys into {@code seen} — an
     * un-reported overflow group is left uncommitted so it surfaces on a later sweep rather than being
     * silently lost.
     */
    private static List<Sighting> reportGroups(Map<String, Group> groups, int cap, LruLongSet seen) {
        if (groups.isEmpty()) return List.of();
        List<Map.Entry<String, Group>> ranked = new ArrayList<>(groups.entrySet());
        ranked.sort((a, b) -> {
            Group ga = a.getValue(), gb = b.getValue();
            if (ga.value != gb.value) return Integer.compare(gb.value, ga.value);   // higher value first
            int dc = Double.compare(ga.dist, gb.dist);
            if (dc != 0) return dc;                                                  // nearer first
            return a.getKey().compareTo(b.getKey());                                // stable tie-break
        });
        List<Sighting> out = new ArrayList<>();
        int n = Math.min(ranked.size(), cap);
        for (int i = 0; i < n; i++) {
            Map.Entry<String, Group> e = ranked.get(i);
            Group g = e.getValue();
            for (long k : g.keys) seen.add(k);   // commit ONLY the reported groups
            out.add(new Sighting(e.getKey(), g.count, g.repX, g.repY, g.repZ));
        }
        return out;
    }

    // ================================================================= format

    /** One grouped sighting line: label ×count @ representative coordinate. Pure data. */
    record Sighting(String label, int count, int x, int y, int z) {}

    /**
     * Render the single Chinese sighting note from the grouped block + entity lists — pure, so it can
     * be asserted without a live world. Example:
     * {@code 路过时留意到:钻石矿×2 @ (x,y,z)、铁矿×5 @ (x2,y2,z2);附近:村民×1 @ (x3,y3,z3)。}
     */
    static String formatSighting(List<Sighting> blocks, List<Sighting> entities) {
        StringBuilder sb = new StringBuilder("路过时留意到:");
        boolean hasBlocks = !blocks.isEmpty();
        if (hasBlocks) sb.append(join(blocks));
        if (!entities.isEmpty()) {
            sb.append(hasBlocks ? ";附近:" : "附近有 ");
            sb.append(join(entities));
        }
        sb.append("。");
        return sb.toString();
    }

    private static String join(List<Sighting> xs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            Sighting s = xs.get(i);
            if (i > 0) sb.append("、");
            sb.append(s.label()).append("×").append(s.count())
                    .append(" @ (").append(s.x()).append(",").append(s.y()).append(",").append(s.z()).append(")");
        }
        return sb.toString();
    }

    // ============================================================ whitelist

    /**
     * The valuable-block whitelist as an id predicate — code-level and easily extended ("packs 可扩展").
     * A tag-driven version was deliberately avoided for v1 so this never touches the shared tag-data /
     * InitTag files. Pure (no MC types), so it is unit-assertable.
     */
    static boolean isValuableBlockId(String path) {
        if (path.endsWith("_ore")) return true;                 // every ore incl. deepslate_/nether_ variants
        if (path.endsWith("shulker_box")) return true;          // shulker_box + all dyed variants
        if ((path.endsWith("_head") || path.endsWith("_skull")) && !path.equals("piston_head")) return true;
        return switch (path) {
            case "ancient_debris",
                 "chest", "trapped_chest", "barrel", "ender_chest",
                 "spawner", "trial_spawner", "vault",
                 "budding_amethyst",
                 "sculk_catalyst", "sculk_shrieker",
                 "suspicious_sand", "suspicious_gravel",
                 "bee_nest", "beehive",
                 "beacon", "conduit",
                 "end_portal_frame", "reinforced_deepslate" -> true;
            default -> false;
        };
    }

    /**
     * Value ranking used when a sighting is truncated — higher is reported first. Pure and deterministic,
     * ordered by first match; only ever called on ids that already passed {@link #isValuableBlockId}.
     */
    static int blockValueScore(String path) {
        if (path.equals("ancient_debris")) return 100;
        if (path.contains("diamond")) return 95;
        if (path.contains("emerald")) return 90;
        if (path.equals("vault")) return 88;
        if (path.equals("trial_spawner") || path.equals("spawner")) return 85;
        if (path.equals("beacon") || path.equals("conduit")) return 82;
        if (path.endsWith("shulker_box") || path.equals("ender_chest")) return 80;
        if (path.contains("gold")) return 75;
        if (path.equals("chest") || path.equals("trapped_chest") || path.equals("barrel")) return 70;
        if (path.equals("end_portal_frame") || path.equals("reinforced_deepslate")) return 68;
        if (path.contains("quartz")) return 62;
        if (path.equals("budding_amethyst")) return 60;
        if (path.endsWith("_head") || path.endsWith("_skull")) return 58;
        if (path.contains("redstone") || path.contains("lapis")) return 52;
        if (path.equals("bee_nest") || path.equals("beehive")) return 50;
        if (path.contains("sculk")) return 48;
        if (path.contains("suspicious")) return 46;
        if (path.contains("iron")) return 44;
        if (path.contains("copper")) return 42;
        if (path.contains("coal")) return 30;
        return 20;
    }

    private static Set<Block> valuableBlocks() {
        Set<Block> c = valuableBlocksCache;
        if (c != null) return c;
        Set<Block> built = new HashSet<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            if (isValuableBlockId(BuiltInRegistries.BLOCK.getKey(b).getPath())) built.add(b);
        }
        valuableBlocksCache = built;
        return built;
    }

    /** Packed pos mixed with the block's registry id, so a rare same-pos replacement is not suppressed. */
    static long blockKey(BlockPos pos, Block block) {
        return pos.asLong() * 1099511628211L + BuiltInRegistries.BLOCK.getId(block);
    }

    // ================================================================ helpers

    private static void ensureCleanup() {
        if (cleanupRegistered) return;
        synchronized (ValuablesSweep.class) {
            if (cleanupRegistered) return;
            // Self-registered cleanup (mirrors CorrectiveNotices.register's self-registration pattern) so
            // no shared registration-point file has to be edited. Independent of Perceptions' own onRemove.
            CompanionLifecycle.onRemove(b -> MEMORIES.remove(b.getUUID()));
            cleanupRegistered = true;
            Constants.LOG.info("[numen-core] ④ valuables sighting sweep registered (cleanup hook)");
        }
    }

    /** Neutralise the XML delimiters so a custom name / item id can't break the {@code <event>} tag. */
    private static String safe(String s) {
        if (s == null) return "";
        return s.replace('&', '＆').replace('<', '（').replace('>', '）');
    }

    // ================================================================ state

    private static final class Memory {
        final LruLongSet blocks = new LruLongSet(SEEN_CAP);
        final LruLongSet entities = new LruLongSet(SEEN_CAP);
    }

    /** A valuable entity's rendered label and its value rank. */
    private record EntDesc(String label, int value) {}

    /** Accumulator for one sighting group: count, best (max) value, nearest representative + member keys. */
    private static final class Group {
        int count;
        int value;
        double dist;
        int repX, repY, repZ;
        final List<Long> keys = new ArrayList<>();

        void observe(long key, int val, double d, BlockPos pos) {
            if (count == 0 || d < dist) { dist = d; repX = pos.getX(); repY = pos.getY(); repZ = pos.getZ(); }
            if (val > value) value = val;
            count++;
            keys.add(key);
        }
    }

    /**
     * A bounded LRU set of packed longs: {@link #contains} peeks without disturbing recency, {@link #add}
     * commits a reported sighting and refreshes it. Beyond {@link #SEEN_CAP} entries the least-recently
     * touched key is evicted, bounding memory and letting long-stale valuables eventually re-surface.
     */
    static final class LruLongSet {
        private final LinkedHashMap<Long, Boolean> map;

        LruLongSet(int cap) {
            this.map = new LinkedHashMap<>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                    return size() > cap;
                }
            };
        }

        /** Peek — LinkedHashMap.containsKey does NOT reorder (only get does), so a check stays neutral. */
        boolean contains(long v) { return map.containsKey(v); }

        /** Commit a reported key (refreshes recency). Returns true if it was newly inserted. */
        boolean add(long v) { return map.put(v, Boolean.TRUE) == null; }

        int size() { return map.size(); }
    }
}
