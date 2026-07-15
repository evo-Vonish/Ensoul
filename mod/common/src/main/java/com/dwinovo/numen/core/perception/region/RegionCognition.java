package com.dwinovo.numen.core.perception.region;

import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.server.level.ServerLevel;

/**
 * The L2 policy driver: turns a region-boundary crossing into append-only
 * observation events, and answers {@code recall_region}. It sits between the
 * cheap, deterministic scan ({@link RegionObservation}) and the append-only
 * store ({@link RegionStore}), applying the dispatch + §5.4 pairing rule of
 * {@code docs/numen-context-design-v1.md} (the canonical contract):
 *
 * <pre>
 *   从未观察  → append REGION_SNAPSHOT v1
 *   hash 相同 → 零追加(复访可附时效提示)
 *   hash 不同 → 结构化 diff → 重要性过滤 →
 *       小变更                       → 仅 REGION_DIFF
 *       结构性变更 / 累计 diff ≥ K /
 *       diff 总量超上一快照 1.5 倍   → REGION_DIFF + 紧随 REGION_SNAPSHOT vN(成对)
 * </pre>
 *
 * <p>The pair's division of labour: the diff answers "why it changed", the fresh
 * snapshot answers "what it is now" — pushed to the tail where attention weight
 * is highest, so the model never has to replay diffs mentally. Snapshot versions
 * are monotonic per region ("同区域以最高版本为准"); old segments are untouched.
 *
 * <p>Events are pushed non-urgent through {@link Companions#emitEvent}, rendered
 * FROM the canonical structure (never hashed prose), and carry
 * {@code provenance="observed"} on the root tag ({@code seq}/{@code schemaVersion}
 * are stamped centrally by the engine's envelope, never here). Server thread only.
 */
public final class RegionCognition {

    /** One game day in ticks — staleness granularity for revisit notes and recall. */
    private static final long GAME_DAY = 24000L;

    /** §5.4 K: accumulated diffs since the last snapshot that force a fresh paired snapshot. */
    private static final int PAIR_AFTER_DIFFS = 8;

    /** §5.4 size trigger: trailing diff bytes above this multiple of the last snapshot pair a new one. */
    private static final double PAIR_SIZE_RATIO = 1.5;

    /**
     * Feature categories whose appearance/disappearance is a STRUCTURAL change (§5.3 importance
     * gate: 容器、工作站、门、危险的变化必追加 — and per §5.4 they also warrant a paired snapshot).
     * A furnace is a workstation in this sense; markers (signs/banners) and generic block
     * entities stay small changes. "door" is future-proofing — v1's block-entity scan never
     * produces it, but the category rule is written where it belongs.
     */
    private static final java.util.Set<String> STRUCTURAL_FEATURE_TYPES =
            java.util.Set.of("container", "furnace", "workstation", "bed", "door");

    private RegionCognition() {}

    /**
     * Observe the region the companion just crossed into and append the appropriate event
     * (or nothing). Caller (the perception poll) has already edge-triggered on the crossing
     * and applied the per-region cooldown.
     */
    public static void observe(NumenPlayer body, ServerLevel level, RegionKey key, long now) {
        RegionObservation obs = RegionObservation.observe(level, key);
        if (obs == null) return;   // chunk not loaded — should not happen for the companion's own region

        RegionStore store = RegionStore.forCompanion(body.getUUID());
        RegionStore.Record rec = store.get(key);

        if (rec == null) {
            // First visit → snapshot v1 (首次进入区域 A → 只追加一个 snapshot).
            store.recordSnapshot(obs, now);
            emit(body, "region_snapshot", key, 1, obs.render());
            return;
        }

        if (rec.semanticHash().equals(obs.semanticHash())) {
            // Unchanged → zero region append (test #4). Optionally note staleness on an old revisit.
            long ageDays = (now - rec.observedAtGameTime()) / GAME_DAY;
            if (ageDays >= 1) {
                emit(body, "region_revisit", key, 0,
                        "你回到了区域 " + key.label() + ",上次观察于约 " + ageDays + " 个游戏日前,信息可能已过时。");
            }
            store.touch(rec, now);   // disk-only observed-time bump, no log append
            return;
        }

        // Hash moved → structured diff against the stored baseline.
        RegionObservation.Diff diff = obs.diffFrom(rec.canonical());
        if (!diff.isSignificant()) {
            // Only insignificant terrain jitter (e.g. a height-band sample shifting by a block) — no
            // event and no canonical/log change, so the stored baseline stays append-only and the
            // recall rebuild stays exact (tests #9, #10). Just bump the observed time on disk.
            store.touch(rec, now);
            return;
        }

        store.recordDiff(rec, obs, diff, now);

        // §5.4 pairing decision — does this diff also warrant a fresh full snapshot?
        //   structural: a container/workstation/bed/door/hazard appeared or vanished, or the
        //               dominant surface / biome changed (the world is *shaped* differently);
        //   tooMany:    ≥ K diffs since the last snapshot (mental replay is getting long);
        //   tooBig:     the trailing diffs outweigh the last snapshot by 1.5× (a fresh
        //               baseline is now cheaper to read than the diff chain).
        boolean structural = isStructural(diff);
        boolean tooMany = rec.diffsSinceBaseline() >= PAIR_AFTER_DIFFS;
        boolean tooBig = rec.trailingDiffJsonChars() > PAIR_SIZE_RATIO * rec.lastBaselineJsonChars();

        if (structural || tooMany || tooBig) {
            // Paired append: diff then snapshot vN, TWO events in the same flush — the diff
            // answers "why", the snapshot answers "what is now" (小变更连续发生 → 仅 diff;
            // 结构性变更 → diff + snapshot 成对追加).
            int version = store.recordSnapshotVersion(rec, obs, now);
            emit(body, "region_diff", key, 0, renderDiff(key, diff));
            emit(body, "region_snapshot", key, version, obs.render());
        } else {
            emit(body, "region_diff", key, 0, renderDiff(key, diff));
        }
    }

    /** Whether a diff is a structural change per the §5.3 importance categories. */
    private static boolean isStructural(RegionObservation.Diff d) {
        if (d.surfaceChanged() || d.biomeChanged()) return true;
        if (!d.addedHazards.isEmpty() || !d.removedHazards.isEmpty()) return true;
        for (RegionObservation.Feature f : d.addedFeatures) {
            if (STRUCTURAL_FEATURE_TYPES.contains(f.type())) return true;
        }
        for (RegionObservation.Feature f : d.removedFeatures) {
            if (STRUCTURAL_FEATURE_TYPES.contains(f.type())) return true;
        }
        return false;
    }

    /**
     * Rebuild and render current cognition for {@code key} from the disk event stream, with a
     * read-time staleness note. Backs the {@code recall_region} tool. Never observes the live
     * world — it reports remembered knowledge, which may be stale.
     */
    public static String recall(NumenPlayer self, RegionKey key, long now) {
        RegionStore store = RegionStore.forCompanion(self.getUUID());
        RegionStore.Record rec = store.get(key);
        if (rec == null) {
            return "你从未观察过该区域 " + key.label() + "。";
        }
        RegionObservation rebuilt = store.rebuild(rec);
        long ageTicks = Math.max(0, now - rec.observedAtGameTime());
        long ageDays = ageTicks / GAME_DAY;
        String staleness = ageDays >= 1
                ? "(上次观察于约 " + ageDays + " 个游戏日前,信息可能已过时)"
                : "(观察于近期)";
        return "区域 " + key.label() + " 的认知" + staleness + ":" + rebuilt.render();
    }

    // ---- rendering ----

    private static String renderDiff(RegionKey key, RegionObservation.Diff d) {
        StringBuilder sb = new StringBuilder("区域 ").append(key.label()).append(" 发生变化。");
        if (!d.addedFeatures.isEmpty()) {
            sb.append("新增:").append(RegionObservation.renderFeatures(d.addedFeatures)).append("。");
        }
        if (!d.removedFeatures.isEmpty()) {
            sb.append("消失:").append(RegionObservation.renderFeatures(d.removedFeatures)).append("。");
        }
        if (d.surfaceChanged()) {
            sb.append("地表由 ").append(RegionObservation.shortId(d.oldSurface))
                    .append(" 变为 ").append(RegionObservation.shortId(d.newSurface)).append("。");
        }
        if (d.biomeChanged()) {
            sb.append("生物群系由 ").append(RegionObservation.shortId(d.oldBiome))
                    .append(" 变为 ").append(RegionObservation.shortId(d.newBiome)).append("。");
        }
        if (!d.addedHazards.isEmpty()) {
            sb.append("新增危险:").append(RegionObservation.renderHazards(d.addedHazards)).append("。");
        }
        if (!d.removedHazards.isEmpty()) {
            sb.append("危险解除:").append(RegionObservation.renderHazards(d.removedHazards)).append("。");
        }
        return sb.toString();
    }

    /**
     * Emit one region event, non-urgent. Root tag carries {@code provenance="observed"} (§3 —
     * these are direct observations) and, for snapshots, the monotonic {@code version} ("同区域
     * 以最高版本为准"). {@code seq} and {@code schemaVersion} belong to the engine's central
     * envelope stamping — never added here.
     */
    private static void emit(NumenPlayer body, String tag, RegionKey key, int version, String inner) {
        StringBuilder open = new StringBuilder("<").append(tag)
                .append(" region=\"").append(key.label()).append("\"");
        if (version > 0) open.append(" version=\"").append(version).append("\"");
        open.append(" provenance=\"observed\">");
        Companions.emitEvent(body, open + inner + "</" + tag + ">", false);
    }
}
