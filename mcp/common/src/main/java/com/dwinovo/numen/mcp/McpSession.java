package com.dwinovo.numen.mcp;

import com.google.gson.JsonElement;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * One connected MCP client's state (W11 — Streamable HTTP session; W14 — the tool-call lifecycle
 * that rides on top of it): its resolved {@link McpConfig.Agent} identity, the per-companion
 * lease tokens THIS session holds, its open SSE stream if it opened one, and enough bookkeeping
 * for {@code notifications/cancelled} and {@code notifications/progress} to find an in-flight
 * call again by its JSON-RPC request id.
 *
 * <h2>Replaces the Wave 3 integrator's stop-gap</h2>
 * Wave 3's integrator patched {@code McpServer} with a single {@code Map<UUID,String>
 * leaseTokens} field keyed only by companion, explicitly documented there as a placeholder "until
 * real McpSession-scoped storage lands". This class is that storage: {@link #leaseTokenFor}/
 * {@link #putLeaseToken} live per session, so two sessions (two different bridge connections —
 * even two tabs of the same agent) never see or clobber each other's tokens the way the old
 * global map could. The task's own instructions call this out explicitly: "Bind MCP sessions to
 * control leases now that ControlRegistry and the reworked NumenActuator exist."
 *
 * <h2>Session identity, not the full W10</h2>
 * This is the slice of "W10 session identity" that Streamable HTTP structurally requires (a
 * session id to hang an SSE stream and per-session lease state off of) — not the complete W10
 * feature (that also covers config-level agent/session plumbing already landed in Wave 1's
 * {@code w1-mcp-auth}). {@link McpServer} sweeps idle sessions using {@link McpConfig
 * #sessionTtlSeconds()}, releasing whatever they held, exactly as that config field's javadoc has
 * promised since Wave 1.
 *
 * <h2>Threading</h2>
 * {@link McpServer} calls every method here from its HTTP executor threads — a session's requests
 * can land on different threads back to back, and the SSE write can race a concurrent
 * notification push — so mutable state is either a concurrent collection or guarded by {@code
 * synchronized}. Nothing here ever touches the Minecraft client thread directly; it only ever
 * holds data {@link com.dwinovo.numen.api.NumenActuator} has already marshalled back.
 */
final class McpSession {

    /**
     * One JSON-RPC {@code tools/call} this session is waiting on — enough state for {@code
     * notifications/cancelled} to complete it early, for a timeout to know which companion/token
     * to send a stop signal for, and for the progress heartbeat to know who to notify and when to
     * stop. {@code progressToken} is {@code null} when the caller's {@code params._meta} carried
     * none — plain elapsed-time notifications are opt-in, per MCP's own convention.
     */
    static final class InFlightCall {
        final UUID companion;
        final String toolName;
        final String sessionToken;
        final CompletableFuture<String> future;
        /** The caller's {@code params._meta.progressToken} verbatim (number or string per the MCP
         *  spec — kept as the original {@link JsonElement} rather than coerced to {@code String}
         *  so the echoed token round-trips with its original JSON type). {@code null} when the
         *  caller didn't ask for progress. */
        final JsonElement progressToken;
        final long startedAtMillis = System.currentTimeMillis();
        volatile ScheduledFuture<?> heartbeat;

        InFlightCall(UUID companion, String toolName, String sessionToken,
                     CompletableFuture<String> future, JsonElement progressToken) {
            this.companion = companion;
            this.toolName = toolName;
            this.sessionToken = sessionToken;
            this.future = future;
            this.progressToken = progressToken;
        }
    }

    final String id;
    /** Null on an unauthenticated loopback connection (no token presented, LAN exposure off). */
    final McpConfig.Agent agent;

    private final Map<UUID, String> leaseTokens = new ConcurrentHashMap<>();
    private final Map<String, InFlightCall> inFlight = new ConcurrentHashMap<>();
    private final Set<String> resourceSubscriptions = ConcurrentHashMap.newKeySet();

    private volatile HttpExchange sseExchange;
    private volatile OutputStream sseOut;
    private final Object sseLock = new Object();

    volatile long lastActivityMillis = System.currentTimeMillis();

    McpSession(String id, McpConfig.Agent agent) {
        this.id = id;
        this.agent = agent;
    }

    /** Human-readable identity for the owner's console/HUD — see {@code ControlRequestPayload}'s
     *  {@code label} field, which this feeds on every {@code acquire_companion}. */
    String agentLabel() {
        return agent != null ? agent.label() : "MCP (unauthenticated loopback)";
    }

    /** Stable identity for {@code ControlRequestPayload}'s {@code controllerId} — distinct per
     *  session even for two connections from the same configured agent, so the server-side
     *  {@code ControlRegistry} lease record is attributable back to exactly this connection. */
    String controllerId() {
        return (agent != null ? agent.id() : "mcp") + ":" + id;
    }

    void touch() {
        lastActivityMillis = System.currentTimeMillis();
    }

    boolean idleLongerThan(long millis) {
        return System.currentTimeMillis() - lastActivityMillis > millis;
    }

    // ==================================================================== per-session lease tokens

    String leaseTokenFor(UUID companion) {
        return leaseTokens.getOrDefault(companion, "");
    }

    void putLeaseToken(UUID companion, String token) {
        leaseTokens.put(companion, token);
    }

    /** Only clears the entry if it still holds {@code expectedToken} — mirrors the old field's
     *  "only clear if it was still OUR token that got released" guard. */
    void clearLeaseToken(UUID companion, String expectedToken) {
        leaseTokens.remove(companion, expectedToken);
    }

    /** Every companion this session currently believes it holds — the idle sweep's release list. */
    Set<UUID> heldCompanions() {
        return Set.copyOf(leaseTokens.keySet());
    }

    // ============================================================== in-flight calls (W14 lifecycle)

    void trackInFlight(String requestId, InFlightCall call) {
        inFlight.put(requestId, call);
    }

    InFlightCall untrackInFlight(String requestId) {
        return inFlight.remove(requestId);
    }

    InFlightCall peekInFlight(String requestId) {
        return inFlight.get(requestId);
    }

    // ===================================================================== resource subscriptions

    void subscribe(String uri) { resourceSubscriptions.add(uri); }

    void unsubscribe(String uri) { resourceSubscriptions.remove(uri); }

    boolean isSubscribed(String uri) { return resourceSubscriptions.contains(uri); }

    Set<String> subscriptions() { return Set.copyOf(resourceSubscriptions); }

    // ================================================================================ SSE (W11)

    boolean hasStream() {
        return sseOut != null;
    }

    void bindStream(HttpExchange exchange, OutputStream out) {
        this.sseExchange = exchange;
        this.sseOut = out;
    }

    /**
     * Write one raw SSE frame (already formatted as {@code "event: ...\ndata: ...\n\n"} or a
     * {@code ": comment\n\n"} keep-alive). Returns {@code false} — and unbinds the stream — the
     * moment the write fails: the client closed the connection, or a proxy dropped it. {@code
     * synchronized} because the periodic keep-alive and an event/progress/resource-update push
     * can race in from different threads onto the same underlying socket.
     */
    boolean writeFrame(String frame) {
        OutputStream out = this.sseOut;
        if (out == null) return false;
        synchronized (sseLock) {
            try {
                out.write(frame.getBytes(StandardCharsets.UTF_8));
                out.flush();
                return true;
            } catch (IOException closedOrBroken) {
                closeStream();
                return false;
            }
        }
    }

    void closeStream() {
        OutputStream out = this.sseOut;
        this.sseOut = null;
        HttpExchange ex = this.sseExchange;
        this.sseExchange = null;
        if (out != null) {
            try { out.close(); } catch (IOException ignored) { /* already gone */ }
        }
        if (ex != null) {
            ex.close();
        }
    }

    /**
     * Everything ending this session (explicit {@code DELETE /mcp}, the idle sweep, a broken SSE
     * stream) must undo on the MCP-protocol side: fail every in-flight call so no caller hangs
     * forever, drop resource subscriptions, close the stream. Deliberately does NOT touch {@link
     * #leaseTokens} — the caller ({@link McpServer}'s session-end path) reads {@link
     * #heldCompanions}/{@link #leaseTokenFor} and fires the actual {@code NumenActuator.release}
     * calls BEFORE calling this method, since once the session is dropped from {@link McpServer}'s
     * session map this object (and {@link #leaseTokens} with it) becomes unreachable garbage
     * anyway — clearing the map here would only race the caller's own read of it for no benefit.
     */
    void teardown(String reason) {
        for (InFlightCall call : inFlight.values()) {
            if (call.heartbeat != null) call.heartbeat.cancel(false);
            call.future.completeExceptionally(new IllegalStateException(reason));
        }
        inFlight.clear();
        resourceSubscriptions.clear();
        closeStream();
    }
}
