package com.dwinovo.numen.client.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Session-scope LLM usage accounting (client-side singleton). Every completed chat
 * completion records its reported token usage here, attributed to the companion whose
 * loop made the call; the Settings tab's Usage view renders the totals. In-memory only —
 * counters reset on relaunch (v1; persistence can layer on later without touching callers).
 *
 * <p>Numbers come from the backend's own usage frames (the same {@code prompt_tokens} /
 * {@code total_tokens} the auto-compaction gate reads), so they reflect what the provider
 * actually metered — no client-side token estimation. Completion tokens are derived as
 * {@code total - prompt}. Requests that fail before a usage frame arrives are not counted;
 * responses that land but get discarded (interrupted turns) ARE counted — the backend
 * billed them regardless.
 *
 * <p>Writes come from the client main thread (both agent-loop response handlers run
 * post-bounce); reads from the render thread. Everything is synchronized anyway — the
 * counters are touched a few times per turn, so contention is irrelevant.
 */
public final class UsageTracker {

    /** Immutable stats snapshot: request count + token totals. */
    public record Stat(int requests, long promptTokens, long completionTokens) {}

    private static final UsageTracker INSTANCE = new UsageTracker();

    public static UsageTracker instance() { return INSTANCE; }

    /** Per-companion accumulators: {@code [requests, promptTokens, completionTokens]}. */
    private final Map<UUID, long[]> perCompanion = new LinkedHashMap<>();
    private long requests, promptTokens, completionTokens;

    private UsageTracker() {}

    /** Record one completed chat completion for {@code companion} (negative inputs clamp to 0). */
    public synchronized void record(UUID companion, int prompt, int completion) {
        long p = Math.max(0, prompt);
        long c = Math.max(0, completion);
        requests++;
        promptTokens += p;
        completionTokens += c;
        long[] acc = perCompanion.computeIfAbsent(companion, k -> new long[3]);
        acc[0]++;
        acc[1] += p;
        acc[2] += c;
    }

    /** Global totals across every companion this session. */
    public synchronized Stat global() {
        return new Stat((int) requests, promptTokens, completionTokens);
    }

    /** Per-companion totals, insertion-ordered (first companion to talk comes first). */
    public synchronized Map<UUID, Stat> perCompanion() {
        Map<UUID, Stat> out = new LinkedHashMap<>();
        for (var e : perCompanion.entrySet()) {
            long[] acc = e.getValue();
            out.put(e.getKey(), new Stat((int) acc[0], acc[1], acc[2]));
        }
        return out;
    }
}
