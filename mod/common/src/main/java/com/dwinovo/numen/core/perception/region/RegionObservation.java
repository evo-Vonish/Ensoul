package com.dwinovo.numen.core.perception.region;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A canonical, deterministic snapshot of what one {@link RegionKey} looks like —
 * the linchpin of the L2 regional-observation layer. Two rules make the whole
 * append-only design work:
 *
 * <ul>
 *   <li><b>Never hash prose.</b> The scan produces a structured observation
 *       (terrain / features / hazards); the {@link #semanticHash()} is computed
 *       from a canonically-ordered serialization of that structure, never from
 *       the natural-language summary. Same world state → same bytes → same hash
 *       (acceptance test #8).</li>
 *   <li><b>Transient state is excluded by construction.</b> Only block entities
 *       (containers / furnaces / workstations — stable infrastructure) and a
 *       fixed 4×4 ground-surface sample enter the structure. Mobs, item drops,
 *       crops, grass/flowers/leaves, fluid levels, lighting and block-entity
 *       <em>contents</em> are never read, so they cannot move the hash
 *       (acceptance test #9). The surface sample uses
 *       {@link Heightmap.Types#MOTION_BLOCKING_NO_LEAVES}, which skips leaves and
 *       non-solid plants, so vegetation churn is filtered before it is seen.</li>
 * </ul>
 *
 * <p>The scan is cheap: 16 precomputed heightmap lookups + 16 block reads + one
 * pass over the chunk's (small) block-entity map — well within the per-observation
 * budget, and only ever run on a region-boundary crossing, never per tick.
 */
public final class RegionObservation {

    /** A stable, meaningful block at a position — a block entity in the region's band. */
    public record Feature(String type, String block, int x, int y, int z) {
        String token() { return type + "|" + block + "|" + x + "|" + y + "|" + z; }
    }

    /** A hazard sampled at the surface (lava / fire / magma). */
    public record Hazard(String type, int x, int y, int z) {
        String token() { return type + "|" + x + "|" + y + "|" + z; }
    }

    private static final Comparator<Feature> FEATURE_ORDER = Comparator
            .comparingInt(Feature::x).thenComparingInt(Feature::y).thenComparingInt(Feature::z)
            .thenComparing(Feature::block);
    private static final Comparator<Hazard> HAZARD_ORDER = Comparator
            .comparingInt(Hazard::x).thenComparingInt(Hazard::y).thenComparingInt(Hazard::z)
            .thenComparing(Hazard::type);

    /** Fixed sample offsets inside the 16-wide region — a deterministic 4×4 grid. */
    private static final int[] GRID = {1, 5, 9, 13};

    /** How far below its column's surface the observer must be to switch to underground sampling. */
    private static final int UNDERGROUND_MARGIN = 4;
    /** Deterministic size caps for the underground stratum sample (bound canonical + diff size). */
    private static final int UNDERGROUND_HAZARD_CAP = 16;
    private static final int UNDERGROUND_ORE_CAP = 24;

    private final RegionKey key;
    /**
     * Which stratum this observation describes: {@code false} = the 4×4 surface sample (v1 behaviour),
     * {@code true} = the region's underground stratum (see {@link #observe}). It selects the prose in
     * {@link #render()}, is persisted for round-trip fidelity, and — since the dual-baseline fix — is
     * the key {@link RegionCognition} uses to pick the region's surface/underground baseline slot in
     * {@link RegionStore.Record}, so a stratum switch is never diffed against the other stratum's
     * baseline (跨层 ≠ 变更). It stays deliberately NOT part of {@link #canonicalString()} — the two
     * strata already diverge on their <em>content</em> (rock walls / lava / ore vs surface block), and
     * keeping it out preserves v1 surface hashes byte-identical across reloads.
     */
    private final boolean underground;
    private final String dominantSurface;
    private final String biome;
    private final int heightMin;
    private final int heightMax;
    private final List<Feature> features;   // canonically sorted
    private final List<Hazard> hazards;     // canonically sorted
    private final String semanticHash;

    private RegionObservation(RegionKey key, boolean underground, String dominantSurface, String biome,
                              int heightMin, int heightMax, List<Feature> features, List<Hazard> hazards) {
        this.key = key;
        this.underground = underground;
        this.dominantSurface = dominantSurface;
        this.biome = biome;
        this.heightMin = heightMin;
        this.heightMax = heightMax;
        features.sort(FEATURE_ORDER);
        hazards.sort(HAZARD_ORDER);
        this.features = List.copyOf(features);
        this.hazards = List.copyOf(hazards);
        this.semanticHash = sha256(canonicalString());
    }

    // ================================================================= scan

    /**
     * Observe {@code key} in {@code level} as seen by an observer at {@code body}. Returns {@code null}
     * if the region's chunk is not loaded (never force-loads — the companion standing in the region
     * guarantees it is loaded when a trigger fires).
     *
     * <p><b>The fix (换眼睛).</b> The content function now samples the observer's ACTUAL stratum. When
     * the body sits well below its column's surface (or in a Y-band below the surface band), it takes a
     * 3D underground sample of the region's own 16×64×16 box — rock walls, visible hazards
     * (lava / fire / magma / water) and notable ores — instead of describing the sky-lit surface it is
     * nowhere near. Otherwise it keeps the original 4×4 surface sample, byte-for-byte. Both feed the
     * SAME canonical structure and the SAME hash, so the diff pipeline revives itself: an underground
     * region now hashes to its real contents, and mining/flooding it moves the hash → REGION_DIFF.
     *
     * <p>Determinism (acceptance test #8): the underground sample iterates a fixed grid in a fixed order
     * and sorts its output with the existing comparators, so the same world state yields byte-identical
     * canonical regardless of iteration order. It is region-scoped (not body-centred), so re-observing an
     * unchanged region from a different spot inside it produces the same hash → zero append (test #4).
     *
     * @param feltOverlay personally-verified hazards (② hurt-seam provenance) to union into the sample,
     *                    deduped by token; may be {@code null}/empty.
     */
    public static RegionObservation observe(ServerLevel level, RegionKey key, BlockPos body, List<Hazard> feltOverlay) {
        BlockPos center = key.centerPos();
        if (!level.hasChunkAt(center)) return null;
        ChunkAccess ca = level.getChunk(key.chunkX(), key.chunkZ(), ChunkStatus.FULL, false);
        if (!(ca instanceof LevelChunk chunk)) return null;

        String biome = level.getBiome(center).unwrapKey()
                .map(k -> k.identifier().toString()).orElse("unknown");

        boolean underground = isUnderground(chunk, key, body);

        String dominantSurface;
        int hMin, hMax;
        List<Feature> features = new ArrayList<>();
        List<Hazard> hazards = new ArrayList<>();

        if (underground) {
            UndergroundSample us = sampleUnderground(level, key);
            dominantSurface = us.dominantSolid();
            hMin = us.solidMinY();
            hMax = us.solidMaxY();
            features.addAll(us.ores());
            hazards.addAll(us.hazards());
        } else {
            // Fixed 4×4 ground-surface sample: height band, dominant surface, surface hazards.
            int shMin = Integer.MAX_VALUE, shMax = Integer.MIN_VALUE;
            Map<String, Integer> surfaceCounts = new HashMap<>();
            int minY = level.getMinY();
            for (int gx : GRID) {
                for (int gz : GRID) {
                    int wx = key.chunkX() * RegionKey.SIZE_XZ + gx;
                    int wz = key.chunkZ() * RegionKey.SIZE_XZ + gz;
                    // Server maintains MOTION_BLOCKING_NO_LEAVES in FINAL_HEIGHTMAPS; it skips leaves and
                    // non-solid plants, filtering vegetation churn before it is ever sampled (test #9).
                    int surfaceY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz) - 1;
                    if (surfaceY < minY) continue;   // void column — nothing to sample
                    if (surfaceY < shMin) shMin = surfaceY;
                    if (surfaceY > shMax) shMax = surfaceY;
                    BlockState st = level.getBlockState(new BlockPos(wx, surfaceY, wz));
                    String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
                    surfaceCounts.merge(id, 1, Integer::sum);
                    String hz = hazardType(id);
                    if (hz != null) hazards.add(new Hazard(hz, wx, surfaceY, wz));
                }
            }
            if (shMin == Integer.MAX_VALUE) { shMin = 0; shMax = 0; }
            hMin = shMin;
            hMax = shMax;
            dominantSurface = majority(surfaceCounts);
        }

        // Features: block entities within the region's Y band (stable infrastructure) — both strata.
        int yLo = key.minY(), yHi = key.maxY();
        for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
            BlockPos p = e.getKey();
            if (p.getY() < yLo || p.getY() >= yHi) continue;
            String id = BuiltInRegistries.BLOCK.getKey(e.getValue().getBlockState().getBlock()).toString();
            features.add(new Feature(featureType(id), id, p.getX(), p.getY(), p.getZ()));
        }

        // ② Felt-hazard overlay: personally-verified hazards union in (deduped by token) so the next
        // snapshot/diff naturally carries what the body was actually hurt by, even if the grid missed it.
        if (feltOverlay != null && !feltOverlay.isEmpty()) {
            Set<String> present = new HashSet<>();
            for (Hazard h : hazards) present.add(h.token());
            for (Hazard h : feltOverlay) if (present.add(h.token())) hazards.add(h);
        }

        return new RegionObservation(key, underground, dominantSurface, biome, hMin, hMax, features, hazards);
    }

    /** Whether the observer at {@code body} is below its column's surface (→ underground sampling). */
    private static boolean isUnderground(LevelChunk chunk, RegionKey key, BlockPos body) {
        int surfaceY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, body.getX(), body.getZ()) - 1;
        int surfaceBand = Math.floorDiv(surfaceY, RegionKey.SIZE_Y);
        return (surfaceY - body.getY() > UNDERGROUND_MARGIN) || (key.ry() < surfaceBand);
    }

    /** Result of the 3D stratum sample over one region's own 16×64×16 box. */
    private record UndergroundSample(String dominantSolid, int solidMinY, int solidMaxY,
                                     List<Feature> ores, List<Hazard> hazards) {}

    /**
     * Sample the region's underground stratum: probe the region band vertically at each of the fixed
     * 4×4 grid columns, recording visible hazards (lava / fire / magma / water — the topmost of each
     * type per column), notable ores, and the dominant wall block. Region-scoped and fixed-order, so
     * two samples of the same world state produce byte-identical canonical (sorted + capped here).
     */
    private static UndergroundSample sampleUnderground(ServerLevel level, RegionKey key) {
        Map<String, Integer> solidCounts = new HashMap<>();
        List<Hazard> hazards = new ArrayList<>();
        List<Feature> ores = new ArrayList<>();
        int solidMin = Integer.MAX_VALUE, solidMax = Integer.MIN_VALUE;
        int yTop = Math.min(key.maxY() - 1, level.getMaxY());
        int yBot = Math.max(key.minY(), level.getMinY());
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int gx : GRID) {
            for (int gz : GRID) {
                int wx = key.chunkX() * RegionKey.SIZE_XZ + gx;
                int wz = key.chunkZ() * RegionKey.SIZE_XZ + gz;
                Set<String> hzThisColumn = new HashSet<>();
                for (int y = yTop; y >= yBot; y--) {
                    p.set(wx, y, wz);
                    BlockState st = level.getBlockState(p);
                    if (st.isAir()) continue;              // the cavity itself — nothing to record
                    String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
                    String hz = undergroundHazard(id, st);
                    if (hz != null) {
                        if (hzThisColumn.add(hz)) hazards.add(new Hazard(hz, wx, y, wz));
                        continue;
                    }
                    if (isOre(id)) { ores.add(new Feature("ore", id, wx, y, wz)); continue; }
                    if (st.getFluidState().isEmpty()) {    // a solid wall block — count it as the stratum rock
                        solidCounts.merge(id, 1, Integer::sum);
                        if (y < solidMin) solidMin = y;
                        if (y > solidMax) solidMax = y;
                    }
                }
            }
        }
        if (solidMin == Integer.MAX_VALUE) { solidMin = yBot; solidMax = yTop; }
        hazards.sort(HAZARD_ORDER);
        ores.sort(FEATURE_ORDER);
        if (hazards.size() > UNDERGROUND_HAZARD_CAP) hazards = new ArrayList<>(hazards.subList(0, UNDERGROUND_HAZARD_CAP));
        if (ores.size() > UNDERGROUND_ORE_CAP) ores = new ArrayList<>(ores.subList(0, UNDERGROUND_ORE_CAP));
        return new UndergroundSample(majority(solidCounts), solidMin, solidMax, ores, hazards);
    }

    // ============================================================ accessors

    public RegionKey key() { return key; }
    public String semanticHash() { return semanticHash; }
    public List<Feature> features() { return features; }
    public List<Hazard> hazards() { return hazards; }

    /** Which stratum this observation describes — the baseline-slot selector (see field doc). */
    public boolean underground() { return underground; }

    // ==================================================== canonical + hash

    /**
     * Deterministic serialization — the exact bytes that get hashed. Field order is
     * fixed and the feature/hazard lists are pre-sorted, so identical world state
     * always yields identical output regardless of scan iteration order or Gson
     * internals (acceptance test #8).
     */
    String canonicalString() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"region\":{\"dimension\":").append(jsonStr(key.dim()))
                .append(",\"x\":").append(key.rx())
                .append(",\"y\":").append(key.ry())
                .append(",\"z\":").append(key.rz()).append("}");
        sb.append(",\"terrain\":{\"dominant_surface\":").append(jsonStr(dominantSurface))
                .append(",\"biome\":").append(jsonStr(biome))
                .append(",\"height_band\":[").append(heightMin).append(",").append(heightMax).append("]}");
        sb.append(",\"features\":[");
        for (int i = 0; i < features.size(); i++) {
            Feature f = features.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"type\":").append(jsonStr(f.type()))
                    .append(",\"block\":").append(jsonStr(f.block()))
                    .append(",\"pos\":[").append(f.x()).append(",").append(f.y()).append(",").append(f.z()).append("]}");
        }
        sb.append("],\"hazards\":[");
        for (int i = 0; i < hazards.size(); i++) {
            Hazard h = hazards.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"type\":").append(jsonStr(h.type()))
                    .append(",\"pos\":[").append(h.x()).append(",").append(h.y()).append(",").append(h.z()).append("]}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());   // SHA-256 is always present; this never runs
        }
    }

    // ============================================================== diff

    /**
     * A structured diff from {@code previous} → {@code this}: which features/hazards
     * appeared or disappeared, and whether terrain changed. {@link #isSignificant()}
     * decides whether it is worth an append (empty diffs and terrain-only band
     * jitter are filtered — acceptance tests #4 &amp; #9).
     */
    public static final class Diff {
        final List<Feature> addedFeatures = new ArrayList<>();
        final List<Feature> removedFeatures = new ArrayList<>();
        final List<Hazard> addedHazards = new ArrayList<>();
        final List<Hazard> removedHazards = new ArrayList<>();
        String oldSurface, newSurface, oldBiome, newBiome;
        int[] oldBand, newBand;

        boolean surfaceChanged() { return !java.util.Objects.equals(oldSurface, newSurface); }
        boolean biomeChanged() { return !java.util.Objects.equals(oldBiome, newBiome); }

        /** Significant if any feature/hazard changed or the dominant surface / biome changed. */
        boolean isSignificant() {
            return !addedFeatures.isEmpty() || !removedFeatures.isEmpty()
                    || !addedHazards.isEmpty() || !removedHazards.isEmpty()
                    || surfaceChanged() || biomeChanged();
        }
    }

    /** Compute the diff of this observation against an earlier {@code previous} one. */
    public Diff diffFrom(RegionObservation previous) {
        Diff d = new Diff();
        d.oldSurface = previous.dominantSurface;
        d.newSurface = this.dominantSurface;
        d.oldBiome = previous.biome;
        d.newBiome = this.biome;
        d.oldBand = new int[]{previous.heightMin, previous.heightMax};
        d.newBand = new int[]{this.heightMin, this.heightMax};

        Set<String> prevF = new LinkedHashSet<>();
        Map<String, Feature> prevFByToken = new HashMap<>();
        for (Feature f : previous.features) { prevF.add(f.token()); prevFByToken.put(f.token(), f); }
        Set<String> curF = new LinkedHashSet<>();
        Map<String, Feature> curFByToken = new HashMap<>();
        for (Feature f : this.features) { curF.add(f.token()); curFByToken.put(f.token(), f); }
        for (String t : curF) if (!prevF.contains(t)) d.addedFeatures.add(curFByToken.get(t));
        for (String t : prevF) if (!curF.contains(t)) d.removedFeatures.add(prevFByToken.get(t));

        Set<String> prevH = new LinkedHashSet<>();
        Map<String, Hazard> prevHByToken = new HashMap<>();
        for (Hazard h : previous.hazards) { prevH.add(h.token()); prevHByToken.put(h.token(), h); }
        Set<String> curH = new LinkedHashSet<>();
        Map<String, Hazard> curHByToken = new HashMap<>();
        for (Hazard h : this.hazards) { curH.add(h.token()); curHByToken.put(h.token(), h); }
        for (String t : curH) if (!prevH.contains(t)) d.addedHazards.add(curHByToken.get(t));
        for (String t : prevH) if (!curH.contains(t)) d.removedHazards.add(prevHByToken.get(t));

        return d;
    }

    /**
     * Apply a stored diff to this (baseline) observation, reconstructing the state after the diff —
     * the step {@code recall_region} uses to rebuild current cognition from the latest snapshot + diffs
     * (acceptance test #10). The result carries the diff's terrain and its post-diff feature set.
     */
    public RegionObservation applyDiff(Diff d) {
        Set<String> removed = new LinkedHashSet<>();
        for (Feature f : d.removedFeatures) removed.add(f.token());
        List<Feature> feats = new ArrayList<>();
        for (Feature f : features) if (!removed.contains(f.token())) feats.add(f);
        feats.addAll(d.addedFeatures);

        Set<String> removedH = new LinkedHashSet<>();
        for (Hazard h : d.removedHazards) removedH.add(h.token());
        List<Hazard> hz = new ArrayList<>();
        for (Hazard h : hazards) if (!removedH.contains(h.token())) hz.add(h);
        hz.addAll(d.addedHazards);

        return new RegionObservation(key, underground, d.newSurface, d.newBiome,
                d.newBand != null ? d.newBand[0] : heightMin,
                d.newBand != null ? d.newBand[1] : heightMax, feats, hz);
    }

    // ============================================================ render

    /** Chinese summary rendered FROM the canonical structure (not the other way around). */
    public String render() {
        return underground ? renderUnderground() : renderSurface();
    }

    private String renderSurface() {
        StringBuilder sb = new StringBuilder();
        sb.append("此处是 ").append(shortId(biome))
                .append(",地表 ").append(shortId(dominantSurface))
                .append(",高度 ").append(heightMin).append("~").append(heightMax).append("。");
        if (features.isEmpty()) {
            sb.append("无显著方块。");
        } else {
            sb.append("显著方块:").append(renderFeatures(features)).append("。");
        }
        if (hazards.isEmpty()) {
            sb.append("无明显危险。");
        } else {
            sb.append("危险:").append(renderHazards(hazards)).append("。");
        }
        return sb.toString();
    }

    /** Underground prose: a cave/mineshaft stratum — rock walls, visible hazards, and ore. */
    private String renderUnderground() {
        StringBuilder sb = new StringBuilder();
        sb.append("此处为地下(").append(shortId(biome)).append("),四周多为 ")
                .append(shortId(dominantSurface))
                .append(",y ").append(heightMin).append("~").append(heightMax).append("。");
        String ores = renderOreCounts(features);
        if (!ores.isEmpty()) sb.append("可见矿物:").append(ores).append("。");
        List<Feature> infra = new ArrayList<>();
        for (Feature f : features) if (!"ore".equals(f.type())) infra.add(f);
        if (!infra.isEmpty()) sb.append("显著方块:").append(renderFeatures(infra)).append("。");
        if (hazards.isEmpty()) {
            sb.append("无明显危险。");
        } else {
            sb.append("危险:").append(renderHazards(hazards)).append("。");
        }
        return sb.toString();
    }

    /** Collapse ore features into "iron_ore×N、coal_ore×M" (insertion-ordered, deterministic). */
    static String renderOreCounts(List<Feature> fs) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (Feature f : fs) if ("ore".equals(f.type())) counts.merge(shortId(f.block()), 1, Integer::sum);
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (!first) sb.append("、");
            sb.append(e.getKey()).append("×").append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    static String renderFeatures(List<Feature> fs) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(fs.size(), 12);   // cap so the note stays short
        for (int i = 0; i < n; i++) {
            Feature f = fs.get(i);
            if (i > 0) sb.append("、");
            sb.append(shortId(f.block())).append("(").append(f.x()).append(",").append(f.y()).append(",").append(f.z()).append(")");
        }
        if (fs.size() > n) sb.append(" 等 ").append(fs.size()).append(" 处");
        return sb.toString();
    }

    static String renderHazards(List<Hazard> hs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hs.size(); i++) {
            Hazard h = hs.get(i);
            if (i > 0) sb.append("、");
            sb.append(h.type()).append("(").append(h.x()).append(",").append(h.y()).append(",").append(h.z()).append(")");
        }
        return sb.toString();
    }

    static String shortId(String id) {
        int c = id.indexOf(':');
        return c >= 0 ? id.substring(c + 1) : id;
    }

    // ============================================================ JSON I/O

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("hash", semanticHash);
        if (underground) o.addProperty("underground", true);   // omitted for surface → v1 files unchanged
        JsonObject terrain = new JsonObject();
        terrain.addProperty("dominant_surface", dominantSurface);
        terrain.addProperty("biome", biome);
        JsonArray band = new JsonArray();
        band.add(heightMin);
        band.add(heightMax);
        terrain.add("height_band", band);
        o.add("terrain", terrain);
        JsonArray fs = new JsonArray();
        for (Feature f : features) {
            JsonObject fo = new JsonObject();
            fo.addProperty("type", f.type());
            fo.addProperty("block", f.block());
            fo.add("pos", posArray(f.x(), f.y(), f.z()));
            fs.add(fo);
        }
        o.add("features", fs);
        JsonArray hs = new JsonArray();
        for (Hazard h : hazards) {
            JsonObject ho = new JsonObject();
            ho.addProperty("type", h.type());
            ho.add("pos", posArray(h.x(), h.y(), h.z()));
            hs.add(ho);
        }
        o.add("hazards", hs);
        return o;
    }

    public static RegionObservation fromJson(RegionKey key, JsonObject o) {
        boolean underground = o.has("underground") && o.get("underground").getAsBoolean();
        JsonObject terrain = o.getAsJsonObject("terrain");
        String surface = terrain.get("dominant_surface").getAsString();
        String biome = terrain.get("biome").getAsString();
        JsonArray band = terrain.getAsJsonArray("height_band");
        int hMin = band.get(0).getAsInt();
        int hMax = band.get(1).getAsInt();
        List<Feature> features = new ArrayList<>();
        for (var el : o.getAsJsonArray("features")) {
            JsonObject fo = el.getAsJsonObject();
            JsonArray p = fo.getAsJsonArray("pos");
            features.add(new Feature(fo.get("type").getAsString(), fo.get("block").getAsString(),
                    p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt()));
        }
        List<Hazard> hazards = new ArrayList<>();
        for (var el : o.getAsJsonArray("hazards")) {
            JsonObject ho = el.getAsJsonObject();
            JsonArray p = ho.getAsJsonArray("pos");
            hazards.add(new Hazard(ho.get("type").getAsString(),
                    p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt()));
        }
        return new RegionObservation(key, underground, surface, biome, hMin, hMax, features, hazards);
    }

    private static JsonArray posArray(int x, int y, int z) {
        JsonArray a = new JsonArray();
        a.add(x);
        a.add(y);
        a.add(z);
        return a;
    }

    // ============================================================ helpers

    private static String majority(Map<String, Integer> counts) {
        String best = "minecraft:air";
        int bestN = -1;
        // Deterministic: ties broken by block id so the same samples always pick the same winner.
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestN || (e.getValue() == bestN && e.getKey().compareTo(best) < 0)) {
                best = e.getKey();
                bestN = e.getValue();
            }
        }
        return best;
    }

    /** Coarse feature bucket from a block id — a stable, render/diff-friendly label. */
    private static String featureType(String id) {
        String p = shortId(id);
        if (p.contains("chest") || p.equals("barrel") || p.contains("shulker_box")
                || p.equals("hopper") || p.equals("dispenser") || p.equals("dropper")) return "container";
        if (p.equals("furnace") || p.equals("blast_furnace") || p.equals("smoker")) return "furnace";
        if (p.equals("brewing_stand") || p.equals("enchanting_table") || p.equals("beacon")
                || p.equals("lectern") || p.equals("bell") || p.equals("campfire") || p.equals("soul_campfire")) return "workstation";
        if (p.contains("bed")) return "bed";
        if (p.contains("sign") || p.contains("banner") || p.contains("skull") || p.contains("head")) return "marker";
        return "block_entity";
    }

    /** A surface hazard label, or null if the sampled block is benign. */
    private static String hazardType(String id) {
        String p = shortId(id);
        if (p.equals("lava")) return "lava";
        if (p.equals("fire") || p.equals("soul_fire")) return "fire";
        if (p.equals("magma_block")) return "magma";
        return null;
    }

    /**
     * Underground hazard label — the surface set plus water (drowning is a real cave hazard, and the
     * flooded lava/water a mineshaft exposes matters). Water is deliberately NOT in {@link #hazardType}
     * so the surface sampler stays byte-identical (a lakeside surface must not sprout water hazards).
     */
    private static String undergroundHazard(String id, BlockState st) {
        String hz = hazardType(id);
        if (hz != null) return hz;
        if (!st.getFluidState().isEmpty()) {
            if (st.getFluidState().is(FluidTags.LAVA)) return "lava";
            if (st.getFluidState().is(FluidTags.WATER)) return "water";
        }
        return null;
    }

    /** Whether a block id names a notable ore (any *_ore variant, plus ancient_debris). */
    private static boolean isOre(String id) {
        String p = shortId(id);
        return p.endsWith("_ore") || p.equals("ancient_debris");
    }

    /** Minimal JSON string escaping for the canonical form (block/biome ids are simple, but be safe). */
    private static String jsonStr(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
