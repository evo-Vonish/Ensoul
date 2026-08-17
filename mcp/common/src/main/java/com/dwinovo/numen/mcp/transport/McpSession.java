package com.dwinovo.numen.mcp.transport;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One connected external brain: its identity, the control leases it holds, and where it has read
 * to in the journal.
 *
 * <h2>Why this type has to exist</h2>
 * The server it replaces had no session concept at all — an agent was re-resolved from a bearer
 * token on every request, and lease tokens lived in one process-wide
 * {@code Map<UUID companion, String token>}. That map is why "Claude Code holds Alice while Codex
 * holds Bob" was not merely unimplemented but <em>unrepresentable</em>: two agents' leases
 * collided in one entry per companion, and a release by either would hand back a token the other
 * was still using.
 *
 * <p>Moving leases here makes the ownership chain explicit and matches the invariant the server
 * already enforces: <b>a lease belongs to a session, a session belongs to an agent, and exactly
 * one brain drives a body.</b> The server-side {@code ControlRegistry} remains the authority —
 * this map is only what this bridge believes it holds, and every tool call re-presents the token
 * for the server to validate. A stale token from a closed session is rejected server-side, so a
 * bug here degrades to "your call is refused", never to two brains driving one body.
 *
 * <h2>Threading</h2>
 * Touched by HTTP worker threads (several concurrently) and the reaper. All mutable state is
 * either atomic or a concurrent map; nothing here blocks, and nothing here touches game state.
 */
public final class McpSession {

    private final String sessionId;
    private final String agentId;
    private final String label;

    /** companion → the lease token this session was granted. See the class doc. */
    private final Map<UUID, String> leases = new ConcurrentHashMap<>();

    /** How far this session has read the journal. Advanced only by its own reads. */
    private final AtomicLong cursor = new AtomicLong(0L);

    private final AtomicLong lastSeenMillis = new AtomicLong(System.currentTimeMillis());

    /** Non-null once {@code GET /mcp} opens a stream; dropped when the peer goes away. */
    private volatile SseSink sink;

    public McpSession(String sessionId, String agentId, String label) {
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.label = label;
    }

    public String sessionId() { return sessionId; }

    /** The configured {@code McpConfig.Agent} id — stable across reconnects, unlike the session id. */
    public String agentId() { return agentId; }

    /** Human-readable controller name, shown on the owner's console: "Claude Code", "Codex". */
    public String label() { return label; }

    public long cursor() { return cursor.get(); }

    /**
     * Advance the read cursor. Monotonic by construction: a batch that arrives out of order (or a
     * client replaying an old cursor) can never rewind this session's position, which would
     * otherwise re-deliver events it has already acted on.
     */
    public void advanceCursor(long to) {
        cursor.accumulateAndGet(to, Math::max);
    }

    public void touch() {
        lastSeenMillis.set(System.currentTimeMillis());
    }

    public long idleMillis() {
        return System.currentTimeMillis() - lastSeenMillis.get();
    }

    // ---- leases ----

    public void rememberLease(UUID companion, String token) {
        if (companion != null && token != null && !token.isBlank()) leases.put(companion, token);
    }

    public void forgetLease(UUID companion) {
        leases.remove(companion);
    }

    /** The token to attach to a tool call for this companion, or {@code ""} when none is held. */
    public String tokenFor(UUID companion) {
        String t = leases.get(companion);
        return t == null ? "" : t;
    }

    public boolean holds(UUID companion) {
        return leases.containsKey(companion);
    }

    /** A snapshot of everything held — used on close to release them all. */
    public Map<UUID, String> leaseSnapshot() {
        return Map.copyOf(leases);
    }

    // ---- SSE ----

    public SseSink sink() {
        SseSink s = sink;
        if (s != null && s.isBroken()) {
            // Self-healing: a dead sink is dropped on first observation rather than retried
            // forever. The session keeps working over cursor reads.
            sink = null;
            return null;
        }
        return s;
    }

    /** Attach a stream, replacing (and closing) any previous one — a reconnect supersedes. */
    public void attach(SseSink newSink) {
        SseSink old = sink;
        sink = newSink;
        if (old != null) old.close();
    }

    public void detach() {
        SseSink s = sink;
        sink = null;
        if (s != null) s.close();
    }
}
