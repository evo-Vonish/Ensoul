package com.dwinovo.numen.core.perception.region;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ③ 移动结算 — the single mechanical hook that re-runs L2 regional observation after a world-action task
 * settles, without any tool opting in ("凡承重必机械:一个机械钩子,不靠工具自觉").
 *
 * <h2>Where it fires</h2>
 * From the pack-side per-task-result seam {@code CorrectiveNotices.onTaskResult}, which
 * {@code CompanionTickDispatcher.drainResults} already invokes for every completed server task (queries
 * reply client-locally and never reach it). So there is no dispatcher edit and no per-tool edit — one
 * hook covers move_to, auto_mine, break_block, place_block, interact_at, hunt … alike.
 *
 * <h2>The two mechanical inputs to {@link MoveSettlePredicate}</h2>
 * <ul>
 *   <li><b>displacement</b> — the body's end-to-end move since the last observation, measured here;</li>
 *   <li><b>world mutation</b> — whether the settled tool is one that mines/places blocks
 *       ({@link #BLOCK_MUTATING_TOOLS}); a mechanical property of the tool evaluated centrally at the
 *       choke, never self-reported. (This stands in for a per-block "挖/放" count: the shared break
 *       primitive {@code BlockDigger} is co-owned/under concurrent edit, so a counter there could not be
 *       committed cleanly; the tool class is the equivalent signal, and zero-append below makes a false
 *       positive harmless.)</li>
 * </ul>
 *
 * <h2>Zero-append preserved</h2>
 * When the predicate trips, {@link RegionCognition#observe} runs for the body's current region; it only
 * appends on a NEW region (snapshot) or a hash change (diff) — an unchanged region re-observed here costs
 * a scan and appends nothing. The position baseline advances only when a re-observe actually fires, so
 * sub-threshold drift accumulates rather than resetting every task.
 *
 * <p><strong>Server thread only</strong> (the task-result drain runs there); the map is concurrent for
 * defensive clarity, matching {@code RegionStore.STORES}.
 */
public final class MoveSettlement {

    /** World-action tools that mine/place blocks in place (may change the region with little movement). */
    private static final Set<String> BLOCK_MUTATING_TOOLS =
            Set.of("break_block", "place_block", "auto_mine", "interact_at");

    private static final class State {
        boolean hasBaseline;
        double baseX, baseY, baseZ;   // body position at the last re-observe
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    private MoveSettlement() {}

    private static State state(UUID id) {
        return STATES.computeIfAbsent(id, k -> new State());
    }

    /** Drop a companion's settlement baseline (from the {@code CompanionLifecycle.onRemove} seam). */
    public static void drop(UUID companionUuid) {
        STATES.remove(companionUuid);
    }

    /**
     * Called once per settled world-action task. Re-observes the body's current region when it has
     * relocated ≥ {@link MoveSettlePredicate#MIN_MOVE} or the tool mined/placed blocks. Never throws into
     * the caller (a settlement miss must not destabilise the task-result path).
     */
    public static void onTaskSettled(NumenPlayer body, String toolName) {
        try {
            if (!(body.level() instanceof ServerLevel level)) return;
            State st = state(body.getUUID());
            Vec3 pos = body.position();
            if (!st.hasBaseline) {
                seed(st, pos);   // first world action — the crossing poll owns the first snapshot
                return;
            }
            double dx = pos.x - st.baseX, dy = pos.y - st.baseY, dz = pos.z - st.baseZ;
            boolean mutated = toolName != null && BLOCK_MUTATING_TOOLS.contains(toolName);
            if (!MoveSettlePredicate.shouldReobserve(dx * dx + dy * dy + dz * dz, mutated)) {
                return;
            }
            RegionKey key = RegionKey.of(level.dimension().identifier().toString(), body.blockPosition());
            RegionCognition.observe(body, level, key, level.getGameTime());
            seed(st, pos);       // advance the baseline only when we actually re-observed
        } catch (Throwable t) {
            Constants.LOG.error("[numen-core] move-settlement re-observation failed for {}", body.getUUID(), t);
        }
    }

    private static void seed(State st, Vec3 pos) {
        st.hasBaseline = true;
        st.baseX = pos.x;
        st.baseY = pos.y;
        st.baseZ = pos.z;
    }
}
