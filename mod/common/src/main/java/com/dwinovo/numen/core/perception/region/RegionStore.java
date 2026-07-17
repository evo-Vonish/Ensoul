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
 * disk.
 *
 * <h2>Dual baselines (surface / underground)</h2>
 * A region's two strata produce disjoint observation content (grass vs rock walls /
 * ore), so each {@link Record} keeps TWO baseline slots — surface and underground —
 * selected by {@link RegionObservation#underground()}. Hash comparison, diffing and
 * the §5.4 pairing bookkeeping are all per-slot; crossing between strata never
 * produces a diff (跨层 ≠ 变更 — the view-dependent false-diff fix). The snapshot
 * version counter stays region-level monotonic across both slots so the event
 * stream never shows a version regression. On-disk schema 2 persists the slots side
 * by side and tags every log entry with its stratum; v1 files (single canonical)
 * migrate on load: the lone canonical seeds its own stratum's slot, the other slot
 * starts empty, and legacy log entries get their stratum inferred (snapshots from
 * their canonical's flag; diffs from their §5.4 paired snapshot, else the running
 * stratum). Existing feltHazards / version counters are always preserved.
 *
 * <h2>Thread context</h2>
 * <strong>Server thread only</strong> — created from the perception poll and the
 * {@code recall_region} tool, both server-side. Keyed by companion UUID (like the
 * engine's {@code LandmarkStore}); a respawn mints a fresh UUID and thus fresh
 * region memory, consistent with landmark memory.
 */
public final class RegionStore {

    /** On-disk record schema: 2 = dual-baseline slots; a record without the field is legacy v1. */
    private static final int SCHEMA_VERSION = 2;

    /** One log entry — an immutable statement of one observation event. */
    public static final class LogEntry {
        final String kind;        // "snapshot" | "diff"
        final int version;        // baseline version (first snapshot = 1, later snapshots = N); 0 for diffs
        final long gameTime;
        final String hash;        // the region's semantic hash after this event
        /** Which stratum this event describes — rebuild/pairing must never mix strata (跨层 ≠ 变更). */
        final boolean underground;
        final RegionObservation canonical;   // full state for a snapshot; null for a diff
        final RegionObservation.Diff diff;    // change set for a diff; null otherwise

        LogEntry(String kind, int version, long gameTime, String hash, boolean underground,
                 RegionObservation canonical, RegionObservation.Diff diff) {
            this.kind = kind;
            this.version = version;
            this.gameTime = gameTime;
            this.hash = hash;
            this.underground = underground;
            this.canonical = canonical;
            this.diff = diff;
        }
    }

    /**
     * The current-state header + append-only history for one region: two per-stratum
     * baseline slots (surface / underground) over one shared event log, felt-hazard
     * overlay, observed-time and region-level version counter.
     */
    public static final class Record {
        /** One stratum's current baseline: canonical obs + hash; empty until that stratum is first seen. */
        static final class Slot {
            String semanticHash;
            RegionObservation canonical;
        }

        final RegionKey key;
        /** Shared: last game time ANY stratum of this region was observed. */
        long observedAtGameTime;
        /** Region-level monotonic snapshot version — never per-slot, so events never show a regression. */
        int baselineVersion;
        final Slot surface = new Slot();
        final Slot underground = new Slot();
        final List<LogEntry> eventLog = new ArrayList<>();
        /** ② Personally-verified hazards (亲测, provenance observed), deduped by type; folded into
         *  every {@link RegionObservation#observe} of this region so a felt danger keeps being carried. */
        final List<RegionObservation.Hazard> feltHazards = new ArrayList<>();

        Record(RegionKey key) { this.key = key; }

        Slot slot(boolean underground) { return underground ? this.underground : surface; }

        public RegionKey key() { return key; }
        public long observedAtGameTime() { return observedAtGameTime; }

        /** Whether this stratum has a baseline yet ({@code false} → first visit of the stratum → snapshot, never diff). */
        public boolean hasBaseline(boolean underground) { return slot(underground).canonical != null; }

        /** This stratum's current semantic hash, or {@code null} if the stratum was never observed. */
        public String semanticHash(boolean underground) { return slot(underground).semanticHash; }

        /** This stratum's current canonical observation, or {@code null} if the stratum was never observed. */
        public RegionObservation canonical(boolean underground) { return slot(underground).canonical; }

        /** The felt-hazard overlay for this region (read-only view is fine — mutated only via the store). */
        public List<RegionObservation.Hazard> feltHazards() { return feltHazards; }

        /** Diffs appended to THIS stratum since its most recent snapshot baseline (other stratum skipped). */
        public int diffsSinceBaseline(boolean underground) {
            int n = 0;
            for (int i = eventLog.size() - 1; i >= 0; i--) {
                LogEntry e = eventLog.get(i);
                if (e.underground != underground) continue;   // interleaved other-stratum events don't reset
                if (e.kind.equals("diff")) n++;
                else break;   // hit this stratum's last baseline
            }
            return n;
        }

        /**
         * Serialized size (chars, a deterministic token proxy) of every diff appended to this stratum
         * since its last snapshot baseline — the "diff 总 token" side of the §5.4 size trigger.
         */
        public int trailingDiffJsonChars(boolean underground) {
            int total = 0;
            for (int i = eventLog.size() - 1; i >= 0; i--) {
                LogEntry e = eventLog.get(i);
                if (e.underground != underground) continue;
                if (!e.kind.equals("diff")) break;
                if (e.diff != null) total += diffToJson(e.diff).toString().length();
            }
            return total;
        }

        /** Serialized size (chars) of this stratum's most recent snapshot baseline — the comparison base. */
        public int lastBaselineJsonChars(boolean underground) {
            for (int i = eventLog.size() - 1; i >= 0; i--) {
                LogEntry e = eventLog.get(i);
                if (e.underground != underground) continue;
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

    /** First visit to the region: record the baseline snapshot (v1) into the observation's stratum slot. */
    public Record recordSnapshot(RegionObservation obs, long now) {
        Record rec = new Record(obs.key());
        rec.observedAtGameTime = now;
        rec.baselineVersion = 1;
        Record.Slot slot = rec.slot(obs.underground());
        slot.semanticHash = obs.semanticHash();
        slot.canonical = obs;
        rec.eventLog.add(new LogEntry("snapshot", 1, now, obs.semanticHash(), obs.underground(), obs, null));
        byKey.put(obs.key().storageKey(), rec);
        save();
        return rec;
    }

    /** A significant SAME-stratum change: append a diff and advance that stratum's baseline slot. */
    public void recordDiff(Record rec, RegionObservation obs, RegionObservation.Diff diff, long now) {
        rec.eventLog.add(new LogEntry("diff", 0, now, obs.semanticHash(), obs.underground(), null, diff));
        Record.Slot slot = rec.slot(obs.underground());
        slot.semanticHash = obs.semanticHash();
        slot.canonical = obs;
        rec.observedAtGameTime = now;
        save();
    }

    /** Confirmed-unchanged revisit: bump the observed time on disk only (no log append — test #4). */
    public void touch(Record rec, long now) {
        rec.observedAtGameTime = now;
        save();
    }

    /**
     * ② Attach a personally-verified hazard to a region's felt overlay, deduped by type (同区域同类型
     * 去重防刷屏 — only the first felt hazard of each type is kept). Returns {@code true} iff it was newly
     * added (the caller then re-observes to fold it into a snapshot/diff); {@code false} = already present.
     */
    public boolean addFeltHazard(Record rec, String type, int x, int y, int z) {
        if (rec == null) return false;
        for (RegionObservation.Hazard h : rec.feltHazards) {
            if (h.type().equals(type)) return false;
        }
        rec.feltHazards.add(new RegionObservation.Hazard(type, x, y, z));
        save();
        return true;
    }

    /**
     * Append a fresh full snapshot baseline into the observation's stratum slot — either the second
     * half of a §5.4 pair (diff answers "why it changed", this snapshot answers "what it is now"), or
     * the FIRST visit to a known region's other stratum (跨层首访 → snapshot, never a cross-stratum
     * diff). The version is region-level monotonic across both slots; older snapshots stay in the log
     * untouched (compaction folds them later, never here). Returns the new version N.
     */
    public int recordSnapshotVersion(Record rec, RegionObservation obs, long now) {
        int version = rec.baselineVersion + 1;
        rec.eventLog.add(new LogEntry("snapshot", version, now, obs.semanticHash(), obs.underground(), obs, null));
        rec.baselineVersion = version;
        Record.Slot slot = rec.slot(obs.underground());
        slot.semanticHash = obs.semanticHash();
        slot.canonical = obs;
        rec.observedAtGameTime = now;
        save();
        return version;
    }

    // ============================================================ rebuild

    /**
     * Rebuild current cognition for ONE stratum of a region from its event stream: start at that
     * stratum's latest snapshot baseline and replay every subsequent diff <em>of the same stratum</em>
     * — the "最新快照 + 其后 diff" replay of §10, layered so a stratum switch in the log is never
     * replayed as a change (跨层 ≠ 变更). This is what {@code recall_region} returns, and for an
     * occupied slot it must reproduce {@code rec.canonical(underground)} exactly. Falls back to the
     * cached slot canonical if the log has no baseline for the stratum; returns {@code null} when the
     * stratum was never observed. Static — pure function of the record, no store state involved.
     */
    public static RegionObservation rebuild(Record rec, boolean underground) {
        int baselineIdx = -1;
        for (int i = rec.eventLog.size() - 1; i >= 0; i--) {
            LogEntry e = rec.eventLog.get(i);
            if (e.underground == underground && e.canonical != null) { baselineIdx = i; break; }
        }
        if (baselineIdx < 0) {
            return rec.canonical(underground);
        }
        RegionObservation current = rec.eventLog.get(baselineIdx).canonical;
        for (int i = baselineIdx + 1; i < rec.eventLog.size(); i++) {
            LogEntry e = rec.eventLog.get(i);
            if (e.underground != underground) continue;   // the other stratum's events are not changes here
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

    /** Serialize one record in the schema-2 dual-slot form. Package-private for the migration self-test. */
    static JsonObject recordToJson(Record rec) {
        JsonObject o = new JsonObject();
        o.addProperty("schema", SCHEMA_VERSION);
        o.addProperty("observedAtGameTime", rec.observedAtGameTime);
        o.addProperty("baselineVersion", rec.baselineVersion);
        if (rec.surface.canonical != null) o.add("surface", slotToJson(rec.surface));
        if (rec.underground.canonical != null) o.add("underground", slotToJson(rec.underground));
        JsonArray log = new JsonArray();
        for (LogEntry e : rec.eventLog) log.add(logEntryToJson(e));
        o.add("eventLog", log);
        if (!rec.feltHazards.isEmpty()) o.add("feltHazards", hazardsToJson(rec.feltHazards));
        return o;
    }

    private static JsonObject slotToJson(Record.Slot slot) {
        JsonObject o = new JsonObject();
        o.addProperty("semanticHash", slot.semanticHash);
        o.add("canonical", slot.canonical.toJson());
        return o;
    }

    /**
     * Deserialize one record. Schema 2 reads the dual slots directly; a record without the
     * {@code schema} field is a legacy v1 single-canonical record and is migrated in place
     * (one-shot, on load — the next save writes schema 2). Package-private for the self-test.
     */
    static Record recordFromJson(RegionKey key, JsonObject o) {
        Record rec = new Record(key);
        rec.observedAtGameTime = o.get("observedAtGameTime").getAsLong();
        rec.baselineVersion = o.has("baselineVersion") ? o.get("baselineVersion").getAsInt() : 1;
        if (o.has("schema")) {
            if (o.has("surface")) slotFromJson(key, o.getAsJsonObject("surface"), rec.surface);
            if (o.has("underground")) slotFromJson(key, o.getAsJsonObject("underground"), rec.underground);
            for (JsonElement el : o.getAsJsonArray("eventLog")) {
                rec.eventLog.add(logEntryFromJson(key, el.getAsJsonObject()));
            }
        } else {
            migrateLegacyRecord(key, o, rec);
        }
        if (o.has("feltHazards")) {
            rec.feltHazards.addAll(hazardsFromJson(o.getAsJsonArray("feltHazards")));
        }
        return rec;
    }

    private static void slotFromJson(RegionKey key, JsonObject o, Record.Slot slot) {
        slot.semanticHash = o.get("semanticHash").getAsString();
        slot.canonical = RegionObservation.fromJson(key, o.getAsJsonObject("canonical"));
    }

    /**
     * One-shot v1 → v2 migration. The legacy record's single canonical seeds the slot of ITS OWN
     * stratum (per its persisted {@code underground} flag); the other slot starts empty — its first
     * post-migration visit appends a fresh snapshot baseline, never a cross-stratum diff. Historic
     * log entries are kept verbatim (content untouched — append-only discipline); each merely gains
     * a stratum tag so per-stratum rebuild/pairing can replay the log without mixing strata:
     * <ul>
     *   <li>snapshots — from their own canonical's {@code underground} flag;</li>
     *   <li>diffs — v1 cross-stratum flips always changed the dominant block → structural → §5.4-paired
     *       with a snapshot carrying the same post-hash, so such a diff inherits that snapshot's
     *       stratum; any other diff stayed within the running stratum. (Residual edge: an unpaired
     *       flip whose dominant block and hazards happened to match across strata would tag to the old
     *       stratum — rare, recall-only, and self-healing at the next same-stratum snapshot; live
     *       diffing always uses the slots, never these tags.)</li>
     * </ul>
     * Version counters, observed time and feltHazards are preserved by the caller.
     */
    private static void migrateLegacyRecord(RegionKey key, JsonObject o, Record rec) {
        RegionObservation canonical = RegionObservation.fromJson(key, o.getAsJsonObject("canonical"));
        Record.Slot slot = rec.slot(canonical.underground());
        slot.semanticHash = o.get("semanticHash").getAsString();
        slot.canonical = canonical;

        JsonArray log = o.getAsJsonArray("eventLog");
        boolean cur = false;   // stratum of the running current state (a v1 log always starts with a snapshot)
        for (int i = 0; i < log.size(); i++) {
            LogEntry parsed = logEntryFromJson(key, log.get(i).getAsJsonObject());
            boolean ug;
            if (parsed.canonical != null) {
                ug = parsed.canonical.underground();
            } else {
                Boolean paired = pairedSnapshotStratum(key, log, i, parsed.hash);
                ug = paired != null ? paired : cur;
            }
            cur = ug;
            rec.eventLog.add(parsed.underground == ug ? parsed
                    : new LogEntry(parsed.kind, parsed.version, parsed.gameTime, parsed.hash, ug,
                                   parsed.canonical, parsed.diff));
        }
    }

    /**
     * If the legacy diff at {@code diffIdx} is §5.4-paired (immediately followed by a snapshot with the
     * same post-hash), the stratum of that snapshot; otherwise {@code null} (unpaired → caller keeps
     * the running stratum).
     */
    private static Boolean pairedSnapshotStratum(RegionKey key, JsonArray log, int diffIdx, String diffHash) {
        if (diffIdx + 1 >= log.size()) return null;
        JsonObject next = log.get(diffIdx + 1).getAsJsonObject();
        if (!next.has("canonical") || !next.has("hash")) return null;
        if (!next.get("hash").getAsString().equals(diffHash)) return null;
        JsonObject nc = next.getAsJsonObject("canonical");
        return nc.has("underground") && nc.get("underground").getAsBoolean();
    }

    private static JsonObject logEntryToJson(LogEntry e) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", e.kind);
        o.addProperty("version", e.version);
        o.addProperty("gameTime", e.gameTime);
        o.addProperty("hash", e.hash);
        if (e.underground) o.addProperty("underground", true);   // omitted for surface entries
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
        // Explicit tag (schema 2) wins; a legacy snapshot falls back to its canonical's own flag
        // (legacy diffs default to surface here and get their real tag inferred by the migration).
        boolean underground = o.has("underground") ? o.get("underground").getAsBoolean()
                : canonical != null && canonical.underground();
        return new LogEntry(kind, version, gameTime, hash, underground, canonical, diff);
    }

    /** Package-private (not private) so the migration self-test can construct realistic diff JSON. */
    static JsonObject diffToJson(RegionObservation.Diff d) {
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
