package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.Constants;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * L1 landmark memory — the canonical, on-disk record of the functional blocks a
 * Numen has used or placed (furnaces, chests, crafting tables, …). This is the
 * "where the important things are" layer of the append-only world-cognition
 * design: the store owns durable state, while {@link LandmarkEventEmitter} turns
 * <em>semantic changes</em> to that state into append-only context events.
 *
 * <h2>Cache-first epistemology</h2>
 * The whole point is a byte-frozen prompt prefix. So the store's context
 * behaviour is:
 * <ul>
 *   <li><b>Re-use of a known landmark</b> (walking back to the same furnace) updates
 *       {@code lastUsedAt}/{@code lastVerified} on disk <em>only</em> — no reorder,
 *       no event, zero prompt-byte change. Using the same station 20 times leaves
 *       the prefix identical (acceptance test #1).</li>
 *   <li><b>A genuinely new coordinate</b> appends an {@code added} landmark event.</li>
 *   <li><b>A confirmed change at a known coordinate</b> (the block is verifiably gone
 *       or replaced, in a loaded chunk) appends a {@code removed} / {@code repurposed}
 *       event; the original {@code added} event is never rewritten (acceptance test #7).</li>
 * </ul>
 *
 * <h2>Verification &amp; faith</h2>
 * {@link #verify} re-checks entries against the live world, but only in
 * <em>loaded</em> chunks and only in the entity's <em>current dimension</em>.
 * An entry in an unloaded chunk (or another dimension) is kept on faith —
 * distance is not evidence of absence (acceptance test #6).
 *
 * <h2>Schema &amp; caps</h2>
 * Each entry carries {@code {id, kind, pos, dim, label?, category, firstSeen,
 * lastUsedAt, lastVerified, note?}}. Labeled landmarks (named by the owner — a
 * future {@code remember_place} tool) are exempt from the cap; unlabeled entries
 * cap at {@link #MAX_UNLABELED}, and evicting an unlabeled one emits <em>nothing</em>
 * — forgetting a nameless station is not a world fact, just a memory limit.
 *
 * <h2>Persistence &amp; migration</h2>
 * One JSON file per entity at {@code config/numen/memory/<uuid>.landmarks.json}.
 * The prior {@code <uuid>.blocks.json} format ({@code [{type,x,y,z}]}) is migrated
 * on first load (dimension defaults to overworld, which the old format omitted).
 * Client main thread only, like everything in this package.
 */
public final class LandmarkStore {

    /** Block id paths worth remembering — interaction infrastructure, not decoration. */
    private static final Set<String> TRACKED_TYPES = Set.of(
            "crafting_table", "furnace", "blast_furnace", "smoker",
            "chest", "barrel", "ender_chest",
            "anvil", "chipped_anvil", "damaged_anvil",
            "grindstone", "stonecutter", "smithing_table",
            "enchanting_table", "brewing_stand", "lodestone");

    /** Cap on remembered <em>unlabeled</em> blocks; labeled landmarks are never capped. */
    private static final int MAX_UNLABELED = 16;

    /** Dimension assumed for entries migrated from the old {@code .blocks.json} (which had none). */
    private static final String MIGRATION_DIM = "minecraft:overworld";

    /** The kind of semantic change an event represents. */
    public enum ChangeType { ADDED, REMOVED, RENAMED, REPURPOSED, POSITION_CORRECTED }

    /** An append-only, immutable statement of a semantic change to a landmark. */
    public record LandmarkEvent(ChangeType type, String id, String kind, String dim,
                                int x, int y, int z, String label) {}

    /** Mutable canonical record for one landmark. Metadata timestamps are wall-clock millis. */
    public static final class Entry {
        String id;
        String kind;        // full block id, e.g. "minecraft:furnace"
        long pos;           // packed BlockPos
        String dim;         // dimension id, e.g. "minecraft:overworld"
        String label;       // owner-given name, or null (no naming tool yet)
        String category;    // coarse bucket (smelting / storage / crafting / utility / workstation)
        long firstSeen;
        long lastUsedAt;
        long lastVerified;
        String note;        // free-form, or null
    }

    private final Path file;
    private final Path legacyFile;
    /** id → entry, insertion-ordered (stable render order — never recency-reshuffled). */
    private final LinkedHashMap<String, Entry> byId = new LinkedHashMap<>();
    /** "dim|packedPos" → id, for O(1) coordinate lookup on record/verify. */
    private final Map<String, String> posIndex = new java.util.HashMap<>();
    /** Semantic changes awaiting emission; drained by the loop at each turn boundary. */
    private final List<LandmarkEvent> pending = new ArrayList<>();
    private int nextId = 1;

    private LandmarkStore(Path file, Path legacyFile) {
        this.file = file;
        this.legacyFile = legacyFile;
        load();
    }

    public static LandmarkStore forEntity(Path memoryDir, UUID entityUuid) {
        return new LandmarkStore(
                memoryDir.resolve(entityUuid + ".landmarks.json"),
                memoryDir.resolve(entityUuid + ".blocks.json"));
    }

    /** Is this block id path a type we remember at all? */
    public static boolean isTracked(String blockPath) {
        return TRACKED_TYPES.contains(blockPath);
    }

    /**
     * Passive harvest from a successful tool result: the Numen used or placed
     * {@code fullKind} (a full block id like {@code minecraft:furnace}) at
     * {@code pos} in dimension {@code dim}. Untracked types are ignored. Behaviour:
     * <ul>
     *   <li>same coord, same kind → {@code lastUsedAt}/{@code lastVerified} bump only
     *       (no event, no reorder — cache-stable re-use);</li>
     *   <li>same coord, different tracked kind → {@code repurposed} event;</li>
     *   <li>new coord → {@code added} event.</li>
     * </ul>
     */
    public void record(String fullKind, BlockPos pos, String dim) {
        String path = pathOf(fullKind);
        if (!isTracked(path)) return;
        if (dim == null || dim.isBlank()) dim = MIGRATION_DIM;
        long now = System.currentTimeMillis();
        String posKey = posKey(dim, pos.asLong());
        String existingId = posIndex.get(posKey);
        if (existingId != null) {
            Entry e = byId.get(existingId);
            if (e.kind.equals(fullKind)) {
                // Re-use of a known landmark: disk-only touch, NO event, NO reorder.
                e.lastUsedAt = now;
                e.lastVerified = now;
                save();
                return;
            }
            // Same spot now holds a different tracked station → repurposed.
            e.kind = fullKind;
            e.category = categoryOf(path);
            e.lastUsedAt = now;
            e.lastVerified = now;
            pending.add(new LandmarkEvent(ChangeType.REPURPOSED, e.id, fullKind, dim,
                    pos.getX(), pos.getY(), pos.getZ(), e.label));
            save();
            return;
        }
        // Genuinely new coordinate → add.
        Entry e = new Entry();
        e.id = "lm_" + String.format("%03d", nextId++);
        e.kind = fullKind;
        e.pos = pos.asLong();
        e.dim = dim;
        e.category = categoryOf(path);
        e.firstSeen = now;
        e.lastUsedAt = now;
        e.lastVerified = now;
        byId.put(e.id, e);
        posIndex.put(posKey, e.id);
        pending.add(new LandmarkEvent(ChangeType.ADDED, e.id, fullKind, dim,
                pos.getX(), pos.getY(), pos.getZ(), e.label));
        Constants.LOG.info("[numen-landmark] added {} {} at {},{},{} ({})",
                e.id, fullKind, pos.getX(), pos.getY(), pos.getZ(), dim);
        evictUnlabeledOverCap();
        save();
    }

    /**
     * Self-heal against the live world for the entity's CURRENT dimension: entries whose
     * block is verifiably gone/changed in a loaded chunk get a {@code removed} event and
     * are dropped; still-present entries get a {@code lastVerified} bump. Entries in
     * unloaded chunks or other dimensions are untouched (kept on faith). Queued events are
     * emitted by the loop; the store never appends to the prompt itself.
     */
    public void verify(Level level, String currentDim) {
        if (level == null || currentDim == null) return;
        long now = System.currentTimeMillis();
        boolean structural = false;
        Iterator<Map.Entry<String, Entry>> it = byId.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            if (!currentDim.equals(e.dim)) continue;          // different dimension — can't verify here
            BlockPos pos = BlockPos.of(e.pos);
            if (!level.hasChunkAt(pos)) continue;             // unloaded — keep on faith (test #6)
            String actual = BuiltInRegistries.BLOCK
                    .getKey(level.getBlockState(pos).getBlock()).getPath();
            if (actual.equals(pathOf(e.kind))) {
                e.lastVerified = now;                         // confirmed present
            } else {
                // Confirmed changed on-site → removed event; original added event stays untouched (test #7).
                pending.add(new LandmarkEvent(ChangeType.REMOVED, e.id, e.kind, e.dim,
                        pos.getX(), pos.getY(), pos.getZ(), e.label));
                Constants.LOG.info("[numen-landmark] removed {} {} at {},{},{} (now {})",
                        e.id, e.kind, pos.getX(), pos.getY(), pos.getZ(), actual);
                it.remove();
                posIndex.remove(posKey(e.dim, e.pos));
                structural = true;
            }
        }
        if (structural) save();
    }

    /** Take and clear the queued semantic-change events (main thread). */
    public List<LandmarkEvent> drainEvents() {
        if (pending.isEmpty()) return List.of();
        List<LandmarkEvent> out = List.copyOf(pending);
        pending.clear();
        return out;
    }

    /**
     * Render the current landmarks as a snapshot body, grouped by dimension and stable in
     * insertion order within each group. Empty string when nothing is known. This is the
     * baseline the model rebuilds cognition from (first turn / post-compaction); ongoing
     * changes ride {@link LandmarkEvent}s instead.
     */
    public String renderSnapshotBody() {
        if (byId.isEmpty()) return "";
        LinkedHashMap<String, List<Entry>> byDim = new LinkedHashMap<>();
        for (Entry e : byId.values()) {
            byDim.computeIfAbsent(e.dim, k -> new ArrayList<>()).add(e);
        }
        StringBuilder sb = new StringBuilder("已知地标(按维度分组):");
        for (Map.Entry<String, List<Entry>> de : byDim.entrySet()) {
            sb.append("\n[").append(de.getKey()).append("]");
            for (Entry e : de.getValue()) {
                BlockPos p = BlockPos.of(e.pos);
                sb.append("\n  ").append(e.id).append(' ').append(e.kind)
                  .append(" (").append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ()).append(')');
                if (e.label != null && !e.label.isBlank()) sb.append(" \"").append(e.label).append('"');
            }
        }
        return sb.toString();
    }

    // ---- internals ----

    /** Drop the oldest-inserted UNLABELED entries beyond the cap. No event: forgetting isn't a world fact. */
    private void evictUnlabeledOverCap() {
        long unlabeled = byId.values().stream().filter(e -> e.label == null).count();
        while (unlabeled > MAX_UNLABELED) {
            String removeId = null;
            for (Entry e : byId.values()) {
                if (e.label == null) { removeId = e.id; break; }   // oldest-inserted unlabeled
            }
            if (removeId == null) break;
            Entry removed = byId.remove(removeId);
            posIndex.remove(posKey(removed.dim, removed.pos));
            unlabeled--;
        }
    }

    private static String posKey(String dim, long packedPos) {
        return dim + "|" + packedPos;
    }

    private static String pathOf(String kind) {
        if (kind == null) return "";
        int c = kind.indexOf(':');
        return c >= 0 ? kind.substring(c + 1) : kind;
    }

    private static String categoryOf(String path) {
        return switch (path) {
            case "furnace", "blast_furnace", "smoker", "brewing_stand" -> "smelting";
            case "chest", "barrel", "ender_chest" -> "storage";
            case "crafting_table", "smithing_table", "stonecutter" -> "crafting";
            case "anvil", "chipped_anvil", "damaged_anvil",
                 "grindstone", "enchanting_table", "lodestone" -> "utility";
            default -> "workstation";
        };
    }

    // ---- persistence ----

    private void load() {
        if (Files.isRegularFile(file)) {
            loadCurrent();
        } else if (Files.isRegularFile(legacyFile)) {
            migrateLegacy();
        }
    }

    private void loadCurrent() {
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (root.has("nextId")) nextId = root.get("nextId").getAsInt();
            JsonArray arr = root.has("landmarks") ? root.getAsJsonArray("landmarks") : new JsonArray();
            for (JsonElement el : arr) {
                Entry e = fromJson(el.getAsJsonObject());
                byId.put(e.id, e);
                posIndex.put(posKey(e.dim, e.pos), e.id);
            }
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.warn("[numen-landmark] failed to load {}: {}", file, ex.toString());
        }
    }

    /** One-time migration from the old {@code .blocks.json} ({@code [{type,x,y,z}]}, no dimension). */
    private void migrateLegacy() {
        try {
            JsonArray arr = JsonParser.parseString(Files.readString(legacyFile, StandardCharsets.UTF_8))
                    .getAsJsonArray();
            long now = System.currentTimeMillis();
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                BlockPos pos = new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
                String type = o.get("type").getAsString();          // old path-only, e.g. "furnace"
                Entry e = new Entry();
                e.id = "lm_" + String.format("%03d", nextId++);
                e.kind = type.indexOf(':') >= 0 ? type : "minecraft:" + type;
                e.pos = pos.asLong();
                e.dim = MIGRATION_DIM;                               // old format had no dimension
                e.category = categoryOf(pathOf(e.kind));
                e.firstSeen = now;
                e.lastUsedAt = now;
                e.lastVerified = 0L;                                 // never verified in the new scheme yet
                byId.put(e.id, e);
                posIndex.put(posKey(e.dim, e.pos), e.id);
            }
            Constants.LOG.info("[numen-landmark] migrated {} entr(ies) from {} → {}",
                    byId.size(), legacyFile.getFileName(), file.getFileName());
            save();
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.warn("[numen-landmark] failed to migrate {}: {}", legacyFile, ex.toString());
        }
    }

    private void save() {
        JsonObject root = new JsonObject();
        root.addProperty("nextId", nextId);
        JsonArray arr = new JsonArray();
        for (Entry e : byId.values()) arr.add(toJson(e));
        root.add("landmarks", arr);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-landmark] failed to save {}: {}", file, ex.toString());
        }
    }

    private static JsonObject toJson(Entry e) {
        BlockPos p = BlockPos.of(e.pos);
        JsonObject o = new JsonObject();
        o.addProperty("id", e.id);
        o.addProperty("kind", e.kind);
        JsonObject pos = new JsonObject();
        pos.addProperty("x", p.getX());
        pos.addProperty("y", p.getY());
        pos.addProperty("z", p.getZ());
        o.add("pos", pos);
        o.addProperty("dim", e.dim);
        if (e.label != null) o.addProperty("label", e.label);
        o.addProperty("category", e.category);
        o.addProperty("firstSeen", e.firstSeen);
        o.addProperty("lastUsedAt", e.lastUsedAt);
        o.addProperty("lastVerified", e.lastVerified);
        if (e.note != null) o.addProperty("note", e.note);
        return o;
    }

    private static Entry fromJson(JsonObject o) {
        Entry e = new Entry();
        e.id = o.get("id").getAsString();
        e.kind = o.get("kind").getAsString();
        JsonObject pos = o.getAsJsonObject("pos");
        e.pos = new BlockPos(pos.get("x").getAsInt(), pos.get("y").getAsInt(), pos.get("z").getAsInt()).asLong();
        e.dim = o.has("dim") ? o.get("dim").getAsString() : MIGRATION_DIM;
        e.label = o.has("label") && !o.get("label").isJsonNull() ? o.get("label").getAsString() : null;
        e.category = o.has("category") ? o.get("category").getAsString() : categoryOf(pathOf(e.kind));
        e.firstSeen = o.has("firstSeen") ? o.get("firstSeen").getAsLong() : 0L;
        e.lastUsedAt = o.has("lastUsedAt") ? o.get("lastUsedAt").getAsLong() : 0L;
        e.lastVerified = o.has("lastVerified") ? o.get("lastVerified").getAsLong() : 0L;
        e.note = o.has("note") && !o.get("note").isJsonNull() ? o.get("note").getAsString() : null;
        return e;
    }
}
