package com.dwinovo.numen.core.perception.region;

import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.server.level.ServerLevel;

/**
 * The L2 policy driver: turns a region-boundary crossing into at most one
 * append-only observation event, and answers {@code recall_region}. It sits
 * between the cheap, deterministic scan ({@link RegionObservation}) and the
 * append-only store ({@link RegionStore}), applying the zero-append semantics of
 * the design:
 *
 * <pre>
 *   从未观察  → append REGION_SNAPSHOT        (test #2)
 *   hash 相同 → 零追加 (revisit may note staleness) (test #4)
 *   hash 不同 → 结构化 diff → 过滤无意义变化 → REGION_DIFF (tests #5, #9)
 *   diff 累积 → append REGION_CHECKPOINT       (test #10)
 * </pre>
 *
 * <p>Events are pushed non-urgent through {@link Companions#emitEvent}, rendered
 * FROM the canonical structure (never hashed prose). Server thread only.
 */
public final class RegionCognition {

    /** One game day in ticks — staleness granularity for revisit notes and recall. */
    private static final long GAME_DAY = 24000L;

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
            // First visit → snapshot (test #2).
            store.recordSnapshot(obs, now);
            emit(body, "region_snapshot", key, 0, obs.render());
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
        emit(body, "region_diff", key, 0, renderDiff(key, diff));

        // Bounded history: once diffs pile up, append a fresh checkpoint baseline (test #10).
        if (store.needsCheckpoint(rec)) {
            int version = store.recordCheckpoint(rec, obs, now);
            emit(body, "region_checkpoint", key, version,
                    "区域认知基线已更新(第 " + version + " 版)。当前状态:" + obs.render());
        }
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

    private static void emit(NumenPlayer body, String tag, RegionKey key, int version, String inner) {
        String open = version > 0
                ? "<" + tag + " region=\"" + key.label() + "\" version=\"" + version + "\">"
                : "<" + tag + " region=\"" + key.label() + "\">";
        Companions.emitEvent(body, open + inner + "</" + tag + ">", false);
    }
}
