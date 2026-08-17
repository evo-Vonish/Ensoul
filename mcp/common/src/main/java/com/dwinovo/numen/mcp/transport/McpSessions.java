package com.dwinovo.numen.mcp.transport;

import com.dwinovo.numen.api.NumenActuator;
import com.dwinovo.numen.mcp.Constants;
import com.dwinovo.numen.mcp.McpConfig;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The live session registry, plus the idle reaper that closes abandoned ones.
 *
 * <h2>The third disconnect net</h2>
 * The control model has three independent ways to notice that a body should be handed back:
 * the owner's game client logging out, the server-side lease TTL, and this. Only this one
 * notices <em>the agent</em> dying while the game keeps running — a desktop agent that crashes,
 * is Ctrl-C'd, or simply wanders off would otherwise leave a companion frozen under a lease
 * nobody is driving, with its built-in brain gated off. That is the exact failure the invariant
 * exists to prevent, seen from the other side.
 *
 * <h2>Closing always releases</h2>
 * Every path out of a session — explicit {@code DELETE}, idle expiry, server shutdown — runs the
 * same release loop. There is deliberately no "close without releasing" method to reach for.
 */
public final class McpSessions {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** How long a release round-trip may take before we stop waiting on it during teardown. */
    private static final long RELEASE_TIMEOUT_SECONDS = 5;

    private final McpConfig config;
    private final Map<String, McpSession> byId = new ConcurrentHashMap<>();

    /**
     * Implicit sessions for clients that do not echo {@code Mcp-Session-Id}, keyed by agent id.
     *
     * <p>A compatibility affordance, and a deliberately narrow one. The MCP spec allows a server
     * to require the header, but a client that ignores it would then be unable to use the bridge
     * at all — an unhelpful way to enforce tidiness. Falling back to one implicit session PER
     * AGENT keeps such clients working while preserving the property that actually matters:
     * Claude Code and Codex authenticate with different tokens, resolve to different agents, and
     * therefore land in different sessions with separate leases. The global-map collision does
     * not come back.
     */
    private final Map<String, String> implicitByAgent = new ConcurrentHashMap<>();

    private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "numen-mcp-reaper");
        t.setDaemon(true);
        return t;
    });

    public McpSessions(McpConfig config) {
        this.config = config;
        long period = Math.max(5, config.sessionTtlSeconds() / 4L);
        reaper.scheduleWithFixedDelay(this::reap, period, period, TimeUnit.SECONDS);
    }

    /** Mint a session for a freshly {@code initialize}d client. */
    public McpSession open(McpConfig.Agent agent) {
        String agentId = agent != null ? agent.id() : "anonymous";
        String label = agent != null ? agent.label() : "MCP (unauthenticated loopback)";
        byte[] raw = new byte[18];
        RANDOM.nextBytes(raw);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        McpSession session = new McpSession(id, agentId, label);
        byId.put(id, session);
        implicitByAgent.put(agentId, id);
        Constants.LOG.info("[numen-mcp] session opened: {} (agent={}, label='{}')", id, agentId, label);
        return session;
    }

    /** Look up by header, or fall back to this agent's implicit session — see {@link #implicitByAgent}. */
    public McpSession resolve(String sessionId, McpConfig.Agent agent) {
        if (sessionId != null && !sessionId.isBlank()) {
            McpSession s = byId.get(sessionId);
            if (s != null) {
                s.touch();
                return s;
            }
        }
        String agentId = agent != null ? agent.id() : "anonymous";
        String implicitId = implicitByAgent.get(agentId);
        if (implicitId != null) {
            McpSession s = byId.get(implicitId);
            if (s != null) {
                s.touch();
                return s;
            }
        }
        return null;
    }

    /** Resolve, or mint one on the spot — for clients that never called {@code initialize}. */
    public McpSession resolveOrOpen(String sessionId, McpConfig.Agent agent) {
        McpSession s = resolve(sessionId, agent);
        return s != null ? s : open(agent);
    }

    public McpSession byId(String sessionId) {
        return sessionId == null ? null : byId.get(sessionId);
    }

    /**
     * Close a session and hand every companion it held back to its baseline brain.
     *
     * <p>Releases are issued through {@link NumenActuator}, which round-trips to the
     * server-authoritative registry — this bridge cannot and must not mutate control state
     * locally. A release that fails is logged and dropped: the server-side lease TTL is the
     * backstop, so a body is never stranded even if this path is interrupted.
     */
    public void close(McpSession session, String why) {
        if (session == null) return;
        byId.remove(session.sessionId());
        implicitByAgent.remove(session.agentId(), session.sessionId());
        session.detach();

        Map<UUID, String> held = session.leaseSnapshot();
        for (Map.Entry<UUID, String> e : held.entrySet()) {
            try {
                NumenActuator.release(e.getKey(), e.getValue())
                        .get(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                Constants.LOG.info("[numen-mcp] released {} on session close ({})", e.getKey(), why);
            } catch (Exception failed) {
                // The server-side TTL will reclaim it; losing this race costs a delay, not a body.
                Constants.LOG.warn("[numen-mcp] could not release {} on session close: {}",
                        e.getKey(), failed.toString());
            }
            session.forgetLease(e.getKey());
        }
        Constants.LOG.info("[numen-mcp] session closed: {} ({}), {} lease(s) released",
                session.sessionId(), why, held.size());
    }

    /** Every live session — the journal pump's fan-out target. */
    public Iterable<McpSession> all() {
        return byId.values();
    }

    public int count() {
        return byId.size();
    }

    private void reap() {
        long ttlMillis = Math.max(30L, config.sessionTtlSeconds()) * 1000L;
        for (McpSession s : byId.values()) {
            // An open SSE stream counts as liveness on its own: a well-behaved client may sit
            // quietly on a long-lived GET without issuing POSTs for minutes, and reaping it would
            // yank a body out from under an agent that is simply thinking.
            if (s.sink() != null) {
                s.touch();
                continue;
            }
            if (s.idleMillis() > ttlMillis) {
                close(s, "idle " + (s.idleMillis() / 1000) + "s");
            }
        }
    }

    /** Server shutdown: close everything, releasing all leases. */
    public void shutdown() {
        for (McpSession s : byId.values()) {
            close(s, "server stopping");
        }
        reaper.shutdownNow();
    }
}
