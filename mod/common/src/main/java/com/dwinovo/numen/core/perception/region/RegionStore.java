package com.dwinovo.numen.core.perception.region;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.platform.Services;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side, per-companion disk store for L2 regional observations — the
 * append-only "what a region looked like when seen" event log. One JSON file per
 * companion at {@code config/numen/memory/<uuid>.regions.json}; the in-memory
 * cache is written back atomically (temp file + move), mirroring
 * {@code CompanionPermissions}'s persistence style.
 *
 * <p><strong>Append-only.</strong> Every region carries an ordered
 * {@code eventLog}: a first-visit {@code snapshot} (v1), then {@code diff}s as the
 * region changes, and — per the §5.4 pairing rule of
 * {@code docs/numen-context-design-v1.md} — fresh full {@code snapshot vN} baselines
 * appended right after a structural diff (monotonic version per region). History is
 * never rewritten — a new baseline is <em>appended</em>, older segments stay on
 * disk. The top-level {@code semanticHash} / {@code canonical} track the current
 * state for a fast unchanged-check (回到 A 世界未变 → 零追加).
 *
 * <h2>Thread context</h2>
 * <strong>Server thread only</strong> — created from the perception poll and the
 * {@code recall_region} tool, both server-side. Keyed by companion UUID (like the
 * engine's {@code LandmarkStore}); a respawn mints a fresh UUID and thus fresh
 * region memory, consistent with landmark memory.
 */
public final class RegionStore {

    /** One log entry — an immutable statement of one observation event. */
    public static final class LogEntry {
        final String kind;        // "snapshot" | "diff"
        final int version;        // baseline version (first snapshot = 1, later snapshots = N); 0 for diffs
        final long gameTime;
        final String hash;        // the region's semantic hash after this event
        final RegionObservation canonical;   // full state for a snapshot; null for a diff
        final RegionObservation.Diff diff;    // change set for a diff; null otherwise

        LogEntry(String kind, int version, long gameTime, String hash,
                 RegionObservation canonical, RegionObservation.Diff diff) {
            this.kind = kind;
            this.version = version;
            this.gameTime = gameTime;
            this.hash = hash;
            this.canonical = canonical;
            this.diff = diff;
        }
    }

    /** The current-state header + append-only history for one region. */
    public static final class Record {
        final RegionKey key;
        String semanticHash;
        long observedAtGameTime;
        RegionObservation canonical;
        int baselineVersion;
        final List<LogEntry> eventLog = new ArrayList<>();

        Record(RegionKey key) { this.key = key; }

        public RegionKey key() { return key; }
        public String semanticHash() { return semanticHash; }
        public long observedAtGameTime() { return observedAtGameTime; }
        public RegionObservation canonical() { return canonical; }

        /** Diffs appended since the most recent snapshot baseline. */
        public int diffsSinceBaseline() {
            int n = 0;
            for (int i = eventLog.size() - 1; i >= 0; i--) {
                LogEntry e = eventLog.get(i);
                if (e.kind.equals("diff")) n++;
                else break;   // hit the last baseline
            }
            return n;
        }

        /**
         * Serialized size (chars, a deterministic token proxy) of every diff appended since
         * the last snapshot baseline — the "diff 总 token" side of the §5.4 size trigger.
         */
        public int trailingDiffJsonChars() {
            int total = 0;
            for (int i = eventLog.size() - 1; i >= 0; i--) {
                LogEntry e = eventLog.get(i);
                if (!e.kind.equals("diff")) break;
                if (e.diff != null) total += diffToJson(e.diff).toString().length();
            }
            return total;
        }

        /** Serialized size (chars) of the most recent snapshot baseline — the comparison base. */
        public int lastBaselineJsonChars() {
            for (int i = eventLog.size() - 1; i >= 0; i--) {
                LogEntry e = eventLog.get(i);
                if (e.canonical != null) return e.canonical.toJson().toString().length();
            }
            return Integer.MAX_VALUE;   // no baseline (shouldn't happen) — never trip the size trigger
        }
    }

    // ---- per-companion registry (server thread) ----
    private static final Map<UUID, RegionStore> STORES = new ConcurrentHashMap<>();

    /** The store for a companion, loaded from disk on first use. */
    public static RegionStore forCompanion(UUID companionUuid) {
        return STORES.computeIfAbsent(companionUuid, RegionStore::new);
    }

    /** Drop a companion's in-memory store (called from the lifecycle onRemove seam). */
    public static void drop(UUID companionUuid) {
        STORES.remove(companionUuid);
    }

    private final Path file;
    /** storageKey → record, insertion-ordered for a stable file. */
    private final LinkedHashMap<String, Record> byKey = new LinkedHashMap<>();

    private RegionStore(UUID companionUuid) {
        this.file = Services.PLATFORM.getConfigDir()
                .resolve("numen").resolve("memory").resolve(companionUuid + ".regions.json");
        load();
    }

    // ================================================================= queries

    public Record get(RegionKey key) {
        return byKey.get(key.storageKey());
    }

    // ============================================================ mutations

    /** First visit: record the baseline snapshot (v1) and append it to the log. */
    public Record recordSnapshot(RegionObservation obs, long now) {
        Record rec = new Record(obs.key());
        rec.semanticHash = obs.semanticHash();
        rec.observedAtGameTime = now;
        rec.canonical = obs;
        rec.baselineVersion = 1;
        rec.eventLog.add(new LogEntry("snapshot", 1, now, obs.semanticHash(), obs, null));
        byKey.put(obs.key().storageKey(), rec);
        save();
        return rec;
    }

    /** A significant change: append a diff and advance the current state. */
    public void recordDiff(Record rec, RegionObservation obs, RegionObservation.Diff diff, long now) {
        rec.eventLog.add(new LogEntry("diff", 0, now, obs.semanticHash(), null, diff));
        rec.semanticHash = obs.semanticHash();
        rec.observedAtGameTime = now;
        rec.canonical = obs;
        save();
    }

    /** Confirmed-unchanged revisit: bump the observed time on disk only (no log append — test #4). */
    public void touch(Record rec, long now) {
        rec.observedAtGameTime = now;
        save();
    }

    /**
     * Append a fresh full snapshot baseline right after a structural diff — the second half of the
     * §5.4 pair (diff answers "why it changed", this snapshot answers "what it is now"). The version
     * is monotonic per region; older snapshots stay in the log untouched (compaction folds them
     * later, never here). Returns the new version N.
     */
    public int recordSnapshotVersion(Record rec, RegionObservation obs, long now) {
        int version = rec.baselineVersion + 1;
        rec.eventLog.add(new LogEntry("snapshot", version, now, obs.semanticHash(), obs, null));
        rec.baselineVersion = version;
        rec.semanticHash = obs.semanticHash();
        rec.observedAtGameTime = now;
        rec.canonical = obs;
        save();
        return version;
    }

    // ============================================================ rebuild

    /**
     * Rebuild current cognition for a region from its event stream: start at the latest
     * (highest-version) snapshot baseline and replay every subsequent diff — the
     * "最新快照 + 其后 diff" replay of §10. This is what {@code recall_region} returns,
     * and it must reproduce {@code rec.canonical} exactly. Falls back to the cached
     * canonical if the log is unusable.
     */
    public RegionObservation rebuild(Record rec) {
        int baselineIdx = -1;
        for (int i = rec.eventLog.size() - 1; i >= 0; i--) {
            if (rec.eventLog.get(i).canonical != null) { baselineIdx = i; break; }
        }
        if (baselineIdx < 0) {
            return rec.canonical;
        }
        RegionObservation current = rec.eventLog.get(baselineIdx).canonical;
        for (int i = baselineIdx + 1; i < rec.eventLog.size(); i++) {
            LogEntry e = rec.eventLog.get(i);
            if (e.kind.equals("diff") && e.diff != null) {
                current = current.applyDiff(e.diff);
            }
        }
        return current;
    }

    // ============================================================ persistence

    private void load() {
        try {
            if (!Files.isRegularFile(file)) return;
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject regions = root.has("regions") ? root.getAsJsonObject("regions") : new JsonObject();
            for (Map.Entry<String, JsonElement> e : regions.entrySet()) {
                RegionKey key = RegionKey.parse(e.getKey());
                byKey.put(e.getKey(), recordFromJson(key, e.getValue().getAsJsonObject()));
            }
        } catch (Exception ex) {
            Constants.LOG.warn("[numen-core] region memory {} unreadable/corrupt; backing up and starting fresh: {}",
                    file.getFileName(), ex.toString());
            backup();
            byKey.clear();
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            JsonObject regions = new JsonObject();
            for (Map.Entry<String, Record> e : byKey.entrySet()) {
                regions.add(e.getKey(), recordToJson(e.getValue()));
            }
            JsonObject root = new JsonObject();
            root.add("regions", regions);
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);

            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ex) {
            Constants.LOG.error("[numen-core] failed to persist region memory {}", file.getFileName(), ex);
        }
    }

    private void backup() {
        try {
            Path bak = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
            Files.move(file, bak, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
            // leave it; the next save() overwrites with valid content
        }
    }

    private static JsonObject recordToJson(Record rec) {
        JsonObject o = new JsonObject();
        o.addProperty("semanticHash", rec.semanticHash);
        o.addProperty("observedAtGameTime", rec.observedAtGameTime);
        o.addProperty("baselineVersion", rec.baselineVersion);
        o.add("canonical", rec.canonical.toJson());
        JsonArray log = new JsonArray();
        for (LogEntry e : rec.eventLog) log.add(logEntryToJson(e));
        o.add("eventLog", log);
        return o;
    }

    private static Record recordFromJson(RegionKey key, JsonObject o) {
        Record rec = new Record(key);
        rec.semanticHash = o.get("semanticHash").getAsString();
        rec.observedAtGameTime = o.get("observedAtGameTime").getAsLong();
        rec.baselineVersion = o.has("baselineVersion") ? o.get("baselineVersion").getAsInt() : 1;
        rec.canonical = RegionObservation.fromJson(key, o.getAsJsonObject("canonical"));
        for (JsonElement el : o.getAsJsonArray("eventLog")) {
            rec.eventLog.add(logEntryFromJson(key, el.getAsJsonObject()));
        }
        return rec;
    }

    private static JsonObject logEntryToJson(LogEntry e) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", e.kind);
        o.addProperty("version", e.version);
        o.addProperty("gameTime", e.gameTime);
        o.addProperty("hash", e.hash);
        if (e.canonical != null) o.add("canonical", e.canonical.toJson());
        if (e.diff != null) o.add("diff", diffToJson(e.diff));
        return o;
    }

    private static LogEntry logEntryFromJson(RegionKey key, JsonObject o) {
        String kind = o.get("kind").getAsString();
        int version = o.has("version") ? o.get("version").getAsInt() : 0;
        long gameTime = o.get("gameTime").getAsLong();
        String hash = o.has("hash") ? o.get("hash").getAsString() : "";
        RegionObservation canonical = o.has("canonical")
                ? RegionObservation.fromJson(key, o.getAsJsonObject("canonical")) : null;
        RegionObservation.Diff diff = o.has("diff") ? diffFromJson(o.getAsJsonObject("diff")) : null;
        return new LogEntry(kind, version, gameTime, hash, canonical, diff);
    }

    private static JsonObject diffToJson(RegionObservation.Diff d) {
        JsonObject o = new JsonObject();
        o.add("addedFeatures", featuresToJson(d.addedFeatures));
        o.add("removedFeatures", featuresToJson(d.removedFeatures));
        o.add("addedHazards", hazardsToJson(d.addedHazards));
        o.add("removedHazards", hazardsToJson(d.removedHazards));
        JsonObject terrain = new JsonObject();
        terrain.addProperty("oldSurface", d.oldSurface);
        terrain.addProperty("newSurface", d.newSurface);
        terrain.addProperty("oldBiome", d.oldBiome);
        terrain.addProperty("newBiome", d.newBiome);
        terrain.add("oldBand", bandToJson(d.oldBand));
        terrain.add("newBand", bandToJson(d.newBand));
        o.add("terrain", terrain);
        return o;
    }

    private static RegionObservation.Diff diffFromJson(JsonObject o) {
        RegionObservation.Diff d = new RegionObservation.Diff();
        d.addedFeatures.addAll(featuresFromJson(o.getAsJsonArray("addedFeatures")));
        d.removedFeatures.addAll(featuresFromJson(o.getAsJsonArray("removedFeatures")));
        d.addedHazards.addAll(hazardsFromJson(o.getAsJsonArray("addedHazards")));
        d.removedHazards.addAll(hazardsFromJson(o.getAsJsonArray("removedHazards")));
        JsonObject terrain = o.getAsJsonObject("terrain");
        d.oldSurface = terrain.get("oldSurface").getAsString();
        d.newSurface = terrain.get("newSurface").getAsString();
        d.oldBiome = terrain.get("oldBiome").getAsString();
        d.newBiome = terrain.get("newBiome").getAsString();
        d.oldBand = bandFromJson(terrain.getAsJsonArray("oldBand"));
        d.newBand = bandFromJson(terrain.getAsJsonArray("newBand"));
        return d;
    }

    private static JsonArray featuresToJson(List<RegionObservation.Feature> fs) {
        JsonArray a = new JsonArray();
        for (RegionObservation.Feature f : fs) {
            JsonObject o = new JsonObject();
            o.addProperty("type", f.type());
            o.addProperty("block", f.block());
            JsonArray p = new JsonArray();
            p.add(f.x());
            p.add(f.y());
            p.add(f.z());
            o.add("pos", p);
            a.add(o);
        }
        return a;
    }

    private static List<RegionObservation.Feature> featuresFromJson(JsonArray a) {
        List<RegionObservation.Feature> out = new ArrayList<>();
        for (JsonElement el : a) {
            JsonObject o = el.getAsJsonObject();
            JsonArray p = o.getAsJsonArray("pos");
            out.add(new RegionObservation.Feature(o.get("type").getAsString(), o.get("block").getAsString(),
                    p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt()));
        }
        return out;
    }

    private static JsonArray hazardsToJson(List<RegionObservation.Hazard> hs) {
        JsonArray a = new JsonArray();
        for (RegionObservation.Hazard h : hs) {
            JsonObject o = new JsonObject();
            o.addProperty("type", h.type());
            JsonArray p = new JsonArray();
            p.add(h.x());
            p.add(h.y());
            p.add(h.z());
            o.add("pos", p);
            a.add(o);
        }
        return a;
    }

    private static List<RegionObservation.Hazard> hazardsFromJson(JsonArray a) {
        List<RegionObservation.Hazard> out = new ArrayList<>();
        for (JsonElement el : a) {
            JsonObject o = el.getAsJsonObject();
            JsonArray p = o.getAsJsonArray("pos");
            out.add(new RegionObservation.Hazard(o.get("type").getAsString(),
                    p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt()));
        }
        return out;
    }

    private static JsonArray bandToJson(int[] band) {
        JsonArray a = new JsonArray();
        if (band != null && band.length == 2) { a.add(band[0]); a.add(band[1]); }
        return a;
    }

    private static int[] bandFromJson(JsonArray a) {
        if (a == null || a.size() != 2) return null;
        return new int[]{a.get(0).getAsInt(), a.get(1).getAsInt()};
    }
}
