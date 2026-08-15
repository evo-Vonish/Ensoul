package com.dwinovo.numen.mcp;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.api.NumenActuator;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A minimal MCP (Model Context Protocol) server, hand-rolled as JSON-RPC 2.0 over the JDK's
 * built-in HTTP server — no third-party MCP SDK, no Reactor. Implements {@code initialize}, {@code
 * ping}, {@code tools/list}, {@code tools/call}, the {@code resources/*} and {@code prompts/*}
 * surfaces, and MCP Streamable HTTP (a real server-initiated {@code GET} SSE stream, not
 * POST-response-only) so notifications, progress, and resource-update pushes can reach a
 * connected agent without it polling.
 *
 * <h2>Transport</h2>
 * Binds a loopback HTTP endpoint at {@code /mcp}. Claude Desktop can't dial an HTTP MCP server
 * directly, so the user points it at this endpoint through the {@code mcp-remote} stdio bridge;
 * every JSON-RPC request/response still rides {@code POST}, while {@code GET /mcp} (with a valid
 * {@code Mcp-Session-Id}) opens the SSE stream and {@code DELETE /mcp} ends a session explicitly.
 * A bare {@code GET /mcp} (no session id) is a lightweight readiness probe — see {@link
 * #respondReadiness}.
 *
 * <h2>Sessions (W11/W14 — bound to control leases)</h2>
 * Every {@code initialize} mints a fresh {@link McpSession}, returned via the {@code
 * Mcp-Session-Id} response header; subsequent requests should send it back. A client that never
 * adopts the header falls back to one persistent per-agent session (see {@link #resolveSession})
 * so simple POST-only callers still keep working exactly as before this wave. Per-companion lease
 * tokens live on the session (not a server-wide map) — see {@link McpSession}'s own doc for why.
 * An idle session (no open SSE stream, no traffic for {@link McpConfig#sessionTtlSeconds()}) is
 * swept, releasing whatever it held, by {@link #sweepIdleSessions}.
 *
 * <h2>Tool surface</h2>
 * Every engine tool (from {@link ToolRegistry}, minus the config's hidden set) is advertised with
 * an extra {@code companion} argument, and calls route to {@link NumenActuator#invoke}. Three
 * management tools — {@code list_companions}, {@code acquire_companion}, {@code release_companion}
 * — wrap the actuator's roster and control methods. Since every call is addressed to a companion
 * and each body runs its tasks independently, an agent can acquire several companions and drive
 * them in parallel.
 *
 * <h2>What this wave delivers vs. what it does not (read before assuming parity)</h2>
 * Delivered: the SSE transport itself; {@code resources/*} and {@code prompts/*}; {@code
 * notifications/progress} (elapsed-time heartbeats — see {@link #sendProgress}, not true
 * percentage completion, which nothing in this file's reach can measure); {@code
 * notifications/cancelled} handling and a timeout that actually stops the body (both via {@link
 * NumenActuator#cancel} — see that method's doc for the one corner it cannot close); enriched
 * {@code list_companions}; and — piggybacked on the existing ~20-tick control-state keep-alive,
 * NOT a true event tee — {@code notifications/numen/control}, {@code notifications/numen/roster},
 * and {@code notifications/numen/lifecycle}. NOT delivered: the world-event stream itself (hurt,
 * low HP, reflex seizure, valuables sighted, ...) — {@code EntityAgentLoop.queueEventNote}, the
 * one tee point the design names, sits outside this wave's file set, so there is no producer to
 * subscribe to. See this wave's followUps for exactly what a future change needs to add.
 */
public final class McpServer {

    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String SERVER_VERSION = "0.2.0";
    private static final java.util.Set<String> SUPPORTED_PROTOCOL_VERSIONS = java.util.Set.of(PROTOCOL_VERSION);

    /** MCP Streamable HTTP's session header — see the class doc's session section. */
    private static final String SESSION_HEADER = "Mcp-Session-Id";

    /** Roster / acquire / release are fast; only tool actions use the config timeout. */
    private static final int CONTROL_TIMEOUT_SECONDS = 10;
    private static final int RESOURCE_READ_TIMEOUT_SECONDS = 10;
    private static final int READINESS_TIMEOUT_SECONDS = 5;
    /** W11 item 1: proxies/clients time out a silent SSE connection well under a minute. */
    private static final int SSE_KEEPALIVE_SECONDS = 15;
    /** How often the idle-session sweep runs — independent of {@link McpConfig#sessionTtlSeconds()},
     *  which is the THRESHOLD a session must sit idle past, not the sweep's own cadence. */
    private static final int SESSION_SWEEP_SECONDS = 30;
    /** W14 item 1: cadence of the elapsed-time {@code notifications/progress} heartbeat. */
    private static final int PROGRESS_INTERVAL_SECONDS = 5;

    /**
     * Lease TTL requested on every {@code acquire_companion} — the server clamps it (60s-20min,
     * see {@code ControlRegistry}) and renews it on every authorized call, so this is only a "how
     * long before an idle caller must be considered gone" budget, not a hard ceiling on a call in
     * progress. 5 minutes taken as a reasonable default; the design doc's openQuestions still
     * flags the right value for the filmed-competition format as undecided (W10).
     */
    private static final int DEFAULT_LEASE_TTL_SECONDS = 300;

    /**
     * Sent to the connecting agent in the {@code initialize} handshake (MCP's {@code instructions}
     * field) — W14 item 6's rewrite for the real contract: acquire is required and refusable,
     * heartbeat/any-call renews the lease, reflexes can seize the body, and — honestly — the
     * world-perception push stream does not exist yet in this build (see the class doc), so an
     * agent is told to keep polling for now rather than promised a channel that will never fire.
     */
    private static final String INSTRUCTIONS = """
            Numen companions are AI-controlled, player-like characters inside a live Minecraft game. \
            Through this server you take control of a companion's body and play the game as it — perceive, \
            move, mine, build, craft, fight. You are the brain; the companion is your hands and eyes, and \
            its own built-in AI steps aside while you drive.

            Loop: (1) list_companions to see who is live, their control state and controller; (2) \
            acquire_companion (by name or id) — this is REQUIRED before any action tool works on a body, \
            and it can be REFUSED if another brain already holds it (the refusal names the current \
            holder); (3) perceive with get_self_status / scan_blocks / scan_nearby_entities, then act \
            with move_to / auto_mine / place_block / craft / equip_item / hunt / etc.; (4) \
            release_companion when done.

            Staying in control: your lease expires after a few minutes of silence — call heartbeat, or \
            simply keep calling tools (any authorized call renews it), to keep it alive. If you go idle \
            too long the built-in brain (or another agent) may resume the body without warning. Survival \
            reflexes (drowning, fire, falling, a close call) can seize the body even while you hold the \
            lease — you will be told via a notifications/numen/control push naming the reason; resume \
            driving once it clears.

            Staying informed: open a GET /mcp Streamable-HTTP stream (send back the Mcp-Session-Id this \
            server handed you at initialize) to receive notifications/numen/control (lease transitions, \
            including yours being revoked), notifications/numen/roster (companions appearing/leaving), \
            notifications/numen/lifecycle (death/respawn), notifications/progress on long-running calls, \
            and notifications/resources/updated for anything you resources/subscribe to. World-perception \
            events (being hurt, items spotted, a reflex firing) are NOT pushed in this build — poll \
            get_self_status / scan_nearby_entities between actions the way you would perceive anything \
            else. Read the numen://protocol/world-cognition resource before trusting any timestamped note \
            you do receive elsewhere: it explains the seq/schemaVersion/provenance envelope and the \
            'same topic, highest seq wins' rule.

            Rules: survival mode — the tools do only what a real player can (mine to get stone; there is \
            no give or setblock). Action tools return only when the task finishes, is cancelled, or times \
            out (a timeout also stops the body — it does not keep running after you give up on it). You \
            can acquire several companions and drive them in parallel. Modded blocks, items, and GUIs \
            (Create, AE2, Mekanism) work natively.""";

    private final McpConfig config;
    private final Gson gson = new Gson();
    private HttpServer http;
    private ExecutorService executor;
    private ScheduledExecutorService scheduler;

    /** Every live session, keyed by the id minted at {@code initialize} (or the synthetic
     *  {@code "implicit:<agent>"} id — see {@link #resolveSession}). */
    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();

    /** Last-seen companion snapshot, for diffing on every control-state push — see {@link
     *  #onControlChanged}. Not a real event tee (see the class doc); this is the best this file's
     *  reach can do for roster/control/lifecycle notifications. */
    private final Map<UUID, NumenActuator.Companion> lastSeen = new ConcurrentHashMap<>();

    /** {@link ClientControl#registerListener} (reached only via {@link
     *  NumenActuator#subscribeControlChanges}) has no unregister — guards against stacking a
     *  second listener if {@link #start} were ever called twice in one client lifetime. */
    private static final AtomicBoolean CONTROL_LISTENER_REGISTERED = new AtomicBoolean(false);

    public McpServer(McpConfig config) {
        this.config = config;
    }

    public void start() throws IOException {
        // A LAN-visible bind with any blank-token agent has no usable credential at
        // all -- refuse to come up rather than silently serving unauthenticated
        // traffic to a Minecraft account. See McpConfig#unguarded.
        if (config.unguarded()) {
            throw new IOException("refusing to bind " + config.host() + ":" + config.port()
                    + " -- this host is not loopback (127.0.0.1/localhost) and at least one configured "
                    + "agent in mcp_server.json has a blank token. Give every agent a token before "
                    + "exposing this server beyond localhost.");
        }
        http = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        http.createContext("/mcp", this::handle);
        // W11 item 4, mandatory: a fixed pool (the old 8) would be entirely consumed by held-open
        // SSE streams and blocking tools/call waits -- both are long-lived by nature and must not
        // share a bounded pool with control traffic (list_companions, release_companion), or those
        // stall behind whatever happened to fill the pool first. Daemon threads only, so an
        // unbounded cached pool never blocks JVM shutdown either.
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "numen-mcp-http");
            t.setDaemon(true);
            return t;
        });
        http.setExecutor(executor);
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "numen-mcp-scheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::sendKeepAlives, SSE_KEEPALIVE_SECONDS, SSE_KEEPALIVE_SECONDS, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::sweepIdleSessions, SESSION_SWEEP_SECONDS, SESSION_SWEEP_SECONDS, TimeUnit.SECONDS);
        if (CONTROL_LISTENER_REGISTERED.compareAndSet(false, true)) {
            NumenActuator.subscribeControlChanges(this::onControlChanged);
        }
        http.start();
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        for (McpSession s : sessions.values()) s.closeStream();
        if (http != null) http.stop(0);
    }

    // ==================================================================================== HTTP layer

    private void handle(HttpExchange ex) {
        try {
            String method = ex.getRequestMethod();
            if ("POST".equalsIgnoreCase(method)) {
                handlePost(ex);
            } else if ("GET".equalsIgnoreCase(method)) {
                handleGet(ex);
            } else if ("DELETE".equalsIgnoreCase(method)) {
                handleDelete(ex);
            } else {
                respond(ex, 405, "");
                ex.close();
            }
        } catch (RuntimeException | IOException fatal) {
            Constants.LOG.warn("[numen-mcp] request failed: {}", fatal.toString());
            try { respond(ex, 500, ""); } catch (IOException ignored) { /* best effort */ }
            ex.close();
        }
    }

    private void handlePost(HttpExchange ex) throws IOException {
        if (!authorized(ex)) { respond(ex, 401, ""); ex.close(); return; }
        // Resolved once per request and threaded through to acquire_companion so the lease is
        // attributed to the connecting brain's own identity, not a single shared "mcp" controller
        // id -- this is what lets the owner's console tell two competing agents apart. Null on a
        // loopback bind with no token presented; McpSession falls back to a generic label/id.
        McpConfig.Agent agent = resolveAgent(ex);
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body);
        } catch (RuntimeException parseErr) {
            respondJson(ex, null, errorResponse(null, -32700, "parse error: " + parseErr.getMessage()));
            ex.close();
            return;
        }

        McpSession session = isInitializeRequest(parsed) ? createSession(agent) : resolveSession(ex, agent);
        if (session == null) {
            respond(ex, 404, "session not found -- call initialize again");
            ex.close();
            return;
        }

        if (parsed.isJsonArray()) {   // JSON-RPC batch
            JsonArray out = new JsonArray();
            for (JsonElement el : parsed.getAsJsonArray()) {
                JsonObject r = dispatch(el.getAsJsonObject(), session);
                if (r != null) out.add(r);
            }
            if (out.isEmpty()) respond(ex, 202, "");
            else respondJson(ex, session, out);
        } else {
            JsonObject r = dispatch(parsed.getAsJsonObject(), session);
            if (r == null) respond(ex, 202, "");   // notification: no response
            else respondJson(ex, session, r);
        }
        ex.close();
    }

    /**
     * {@code GET /mcp}: with a valid {@link #SESSION_HEADER}, opens the Streamable-HTTP SSE
     * stream and deliberately does NOT close {@code ex} — the whole point is to hold it open until
     * the client disconnects ({@link McpSession#writeFrame} detects that), the session ends
     * (explicit {@code DELETE}), or the idle sweep reaps it. Without the header, answers the W11
     * item 7 readiness probe instead.
     */
    private void handleGet(HttpExchange ex) throws IOException {
        if (!authorized(ex)) { respond(ex, 401, ""); ex.close(); return; }
        String header = ex.getRequestHeaders().getFirst(SESSION_HEADER);
        if (header == null || header.isBlank()) {
            respondReadiness(ex);
            ex.close();
            return;
        }
        McpSession session = sessions.get(header);
        if (session == null) {
            respond(ex, 404, "session not found -- call initialize again");
            ex.close();
            return;
        }
        session.touch();
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.getResponseHeaders().add("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);   // 0 == chunked: an unbounded stream, not a fixed body
        session.bindStream(ex, ex.getResponseBody());
    }

    private void handleDelete(HttpExchange ex) throws IOException {
        if (!authorized(ex)) { respond(ex, 401, ""); ex.close(); return; }
        String header = ex.getRequestHeaders().getFirst(SESSION_HEADER);
        if (header == null || header.isBlank()) {
            respond(ex, 400, SESSION_HEADER + " header required");
            ex.close();
            return;
        }
        McpSession session = sessions.get(header);
        if (session == null) {
            respond(ex, 404, "");
            ex.close();
            return;
        }
        endSession(session, "session ended by client (DELETE /mcp)");
        respond(ex, 204, "");
        ex.close();
    }

    /** W11 item 7: a bare {@code GET /mcp}, no session id, no JSON-RPC framing needed — lets an
     *  agent confirm the port is alive and a world is loaded before crafting a real request. Today
     *  an empty {@code list_companions} result is ambiguous ("no companions yet" vs. "game is at
     *  the main menu"); {@code world_loaded} disambiguates. */
    private void respondReadiness(HttpExchange ex) throws IOException {
        JsonObject o = new JsonObject();
        try {
            boolean worldLoaded = NumenActuator.worldLoaded().get(READINESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int companionCount = worldLoaded
                    ? NumenActuator.companions().get(READINESS_TIMEOUT_SECONDS, TimeUnit.SECONDS).size() : 0;
            o.addProperty("ok", true);
            o.addProperty("world_loaded", worldLoaded);
            o.addProperty("owner_in_game", worldLoaded);
            o.addProperty("companions", companionCount);
        } catch (Exception ex2) {
            o.addProperty("ok", false);
            o.addProperty("error", String.valueOf(ex2.getMessage()));
        }
        byte[] bytes = gson.toJson(o).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Loopback keeps the original zero-friction behaviour in this pass: the per-agent schema is
     * migrated and a token is minted regardless of {@code host}, but enforcement only turns on
     * once the bind is network-visible, so an existing local setup is not broken by the migration
     * itself. The instant {@code host} is LAN-exposed, every request must resolve to a real
     * per-agent token: see {@link McpConfig#agentForToken} for the equality (never substring)
     * comparison, and {@link McpConfig#unguarded} for the startup-time refusal that backstops this
     * at the config level. Applied identically to POST/GET/DELETE.
     */
    private boolean authorized(HttpExchange ex) {
        if (!config.lanExposed()) return true;
        return resolveAgent(ex) != null;
    }

    /** The agent that owns the bearer/query token on this request, or null if none was presented
     *  or it matches no configured agent. */
    private McpConfig.Agent resolveAgent(HttpExchange ex) {
        return config.agentForToken(presentedToken(ex));
    }

    /** Authorization header first, then the query string's {@code token=} parameter — equality only. */
    private static String presentedToken(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring("Bearer ".length());
        }
        String query = ex.getRequestURI().getQuery();
        if (query == null || query.isEmpty()) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (!"token".equals(key)) continue;
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException malformed) {
                return value; // malformed percent-encoding — fall back to the raw (still equality-compared) value
            }
        }
        return null;
    }

    private void respondJson(HttpExchange ex, McpSession session, JsonElement json) throws IOException {
        byte[] bytes = gson.toJson(json).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        if (session != null) ex.getResponseHeaders().add(SESSION_HEADER, session.id);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    // ============================================================================== session lifecycle

    private static boolean isInitializeRequest(JsonElement parsed) {
        JsonObject o = null;
        if (parsed.isJsonObject()) {
            o = parsed.getAsJsonObject();
        } else if (parsed.isJsonArray() && !parsed.getAsJsonArray().isEmpty()
                && parsed.getAsJsonArray().get(0).isJsonObject()) {
            o = parsed.getAsJsonArray().get(0).getAsJsonObject();
        }
        return o != null && o.has("method") && "initialize".equals(o.get("method").getAsString());
    }

    private McpSession createSession(McpConfig.Agent agent) {
        String id = UUID.randomUUID().toString();
        McpSession session = new McpSession(id, agent);
        sessions.put(id, session);
        return session;
    }

    /** Header present and known → that session. Header present but unknown → {@code null} (caller
     *  responds 404, per Streamable HTTP: the client must {@code initialize} again). Header absent
     *  → one persistent implicit session per resolved identity, so a client that never adopts
     *  {@code Mcp-Session-Id} keeps the exact behaviour it had before this wave (a lease token that
     *  survives across its calls) rather than losing state on every request. */
    private McpSession resolveSession(HttpExchange ex, McpConfig.Agent agent) {
        String header = ex.getRequestHeaders().getFirst(SESSION_HEADER);
        if (header != null && !header.isBlank()) {
            return sessions.get(header);
        }
        String implicitId = "implicit:" + (agent != null ? agent.id() : "anon");
        return sessions.computeIfAbsent(implicitId, k -> new McpSession(implicitId, agent));
    }

    /** Release every companion the session believed it held, then tear down its MCP-protocol
     *  state (fail in-flight calls, drop subscriptions, close the stream). Used by both the
     *  explicit {@code DELETE /mcp} path and {@link #sweepIdleSessions}. */
    private void endSession(McpSession session, String reason) {
        sessions.remove(session.id, session);
        for (UUID companion : session.heldCompanions()) {
            String token = session.leaseTokenFor(companion);
            if (!token.isEmpty()) {
                NumenActuator.release(companion, token);   // best-effort; nothing to react to here
            }
        }
        session.teardown(reason);
    }

    private void sweepIdleSessions() {
        long ttlMillis = Math.max(1, config.sessionTtlSeconds()) * 1000L;
        for (McpSession s : List.copyOf(sessions.values())) {
            if (s.hasStream()) continue;   // an open SSE stream IS activity; never sweep it out from under itself
            if (s.idleLongerThan(ttlMillis)) {
                endSession(s, "idle longer than " + config.sessionTtlSeconds() + "s");
            }
        }
    }

    private void sendKeepAlives() {
        for (McpSession s : sessions.values()) {
            if (s.hasStream()) {
                s.writeFrame(": keep-alive\n\n");   // false return already unbinds the stream internally
            }
        }
    }

    // ==================================================================================== JSON-RPC dispatch

    /** @return the response object, or null for a notification (no id → no reply). */
    private JsonObject dispatch(JsonObject req, McpSession session) {
        JsonElement id = req.get("id");
        String method = req.has("method") ? req.get("method").getAsString() : "";
        session.touch();

        if (id == null || id.isJsonNull()) {
            if ("notifications/cancelled".equals(method)) {
                handleCancelled(req, session);
            }
            // notifications/initialized and anything else unrecognised: ignored, per spec — a
            // notification never gets a response either way.
            return null;
        }

        try {
            return switch (method) {
                case "initialize" -> okResponse(id, initializeResult(req));
                case "ping" -> okResponse(id, new JsonObject());
                case "tools/list" -> okResponse(id, toolsListResult());
                case "tools/call" -> okResponse(id, toolsCallResult(req, id, session));
                case "resources/list" -> okResponse(id, McpResources.toListJson(McpResources.listStatic(config)));
                case "resources/templates/list" -> okResponse(id, McpResources.toTemplatesJson(McpResources.TEMPLATES));
                case "resources/read" -> resourcesReadResponse(req, id);
                case "resources/subscribe" -> resourcesSubscribeResponse(req, id, session, true);
                case "resources/unsubscribe" -> resourcesSubscribeResponse(req, id, session, false);
                case "prompts/list" -> promptsListResponse(id);
                case "prompts/get" -> promptsGetResponse(req, id);
                default -> errorResponse(id, -32601, "method not found: " + method);
            };
        } catch (RuntimeException ex) {
            return errorResponse(id, -32603, "internal error: " + ex.getMessage());
        }
    }

    private JsonObject initializeResult(JsonObject req) {
        String clientProto = PROTOCOL_VERSION;
        if (req.has("params") && req.get("params").isJsonObject()) {
            JsonObject p = req.getAsJsonObject("params");
            if (p.has("protocolVersion")) {
                // W11 item 5: negotiate, don't echo -- a version we don't implement gets OUR
                // version back, not a pretence that we support whatever the client asked for.
                String requested = p.get("protocolVersion").getAsString();
                clientProto = SUPPORTED_PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSION;
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", clientProto);
        JsonObject caps = new JsonObject();
        JsonObject toolsCap = new JsonObject();
        toolsCap.addProperty("listChanged", true);
        caps.add("tools", toolsCap);
        JsonObject resourcesCap = new JsonObject();
        resourcesCap.addProperty("subscribe", true);
        resourcesCap.addProperty("listChanged", true);
        caps.add("resources", resourcesCap);
        JsonObject promptsCap = new JsonObject();
        promptsCap.addProperty("listChanged", true);
        caps.add("prompts", promptsCap);
        caps.add("logging", new JsonObject());
        result.add("capabilities", caps);
        JsonObject info = new JsonObject();
        info.addProperty("name", "numen-mcp");
        info.addProperty("version", SERVER_VERSION);
        result.add("serverInfo", info);
        result.addProperty("instructions", INSTRUCTIONS);
        return result;
    }

    // ---- tools/list ----

    private JsonObject toolsListResult() {
        JsonArray tools = new JsonArray();

        tools.add(toolDef("list_companions",
                "List the owner's live Minecraft companions: name, id, control state, controller, TTL, "
                        + "alive/respawn countdown, dimension and HP. Call this first to see who you can drive.",
                objectSchema(null, false)));
        tools.add(toolDef("acquire_companion",
                "Take control of a companion so you (not its built-in AI) drive it. Pauses its built-in brain and "
                        + "frees its body. REQUIRED before any action tool works on it; refused (not stolen) if "
                        + "another brain already holds it.",
                objectSchema("companion", true)));
        tools.add(toolDef("release_companion",
                "Hand a companion back to its built-in AI when you are done driving it.",
                objectSchema("companion", true)));

        for (NumenTool tool : ToolRegistry.all()) {
            if (config.isHidden(tool.name())) continue;
            tools.add(toolDef(tool.name(), tool.description(), withCompanion(tool.parameterSchema())));
        }

        JsonObject result = new JsonObject();
        result.add("tools", tools);
        return result;
    }

    private JsonObject toolDef(String name, String description, JsonObject inputSchema) {
        JsonObject t = new JsonObject();
        t.addProperty("name", name);
        t.addProperty("description", description);
        t.add("inputSchema", inputSchema);
        return t;
    }

    /** An object schema with just an optional required {@code companion} string. */
    private JsonObject objectSchema(String companionKey, boolean required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        if (companionKey != null) props.add(companionKey, companionProp());
        schema.add("properties", props);
        if (companionKey != null && required) {
            JsonArray req = new JsonArray();
            req.add(companionKey);
            schema.add("required", req);
        }
        return schema;
    }

    /** An engine tool's own schema, with a required {@code companion} argument injected. */
    private JsonObject withCompanion(java.util.Map<String, Object> parameterSchema) {
        JsonObject schema = parameterSchema == null
                ? new JsonObject()
                : gson.toJsonTree(parameterSchema).getAsJsonObject();
        schema.addProperty("type", "object");
        JsonObject props = schema.has("properties") && schema.get("properties").isJsonObject()
                ? schema.getAsJsonObject("properties") : new JsonObject();
        props.add("companion", companionProp());
        schema.add("properties", props);
        JsonArray required = schema.has("required") && schema.get("required").isJsonArray()
                ? schema.getAsJsonArray("required") : new JsonArray();
        boolean has = false;
        for (JsonElement e : required) if ("companion".equals(e.getAsString())) has = true;
        if (!has) required.add("companion");
        schema.add("required", required);
        return schema;
    }

    private JsonObject companionProp() {
        JsonObject p = new JsonObject();
        p.addProperty("type", "string");
        p.addProperty("description", "Which companion to act with — its name or id (see list_companions).");
        return p;
    }

    // ---- tools/call ----

    private JsonObject toolsCallResult(JsonObject req, JsonElement id, McpSession session) {
        JsonObject params = req.has("params") && req.get("params").isJsonObject()
                ? req.getAsJsonObject("params") : new JsonObject();
        String name = params.has("name") ? params.get("name").getAsString() : "";
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();
        // W14 item 1: params._meta.progressToken, previously never parsed at all.
        JsonElement progressToken = null;
        if (params.has("_meta") && params.get("_meta").isJsonObject()) {
            JsonObject meta = params.getAsJsonObject("_meta");
            if (meta.has("progressToken") && !meta.get("progressToken").isJsonNull()) {
                progressToken = meta.get("progressToken");
            }
        }

        try {
            return switch (name) {
                case "list_companions" -> content(listCompanions(), false);
                case "acquire_companion" -> handleControl(args, true, session);
                case "release_companion" -> handleControl(args, false, session);
                default -> handleToolInvoke(name, args, id, session, progressToken);
            };
        } catch (TimeoutException te) {
            return content("timed out waiting for the action to finish", true);
        } catch (Exception ex) {
            return content("call failed: " + ex.getMessage(), true);
        }
    }

    private String listCompanions() throws Exception {
        List<NumenActuator.Companion> list = NumenActuator.companions()
                .get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (list.isEmpty()) {
            return "No companions are live in the world right now. Summon one in-game first.";
        }
        StringBuilder sb = new StringBuilder("Live companions:\n");
        for (NumenActuator.Companion c : list) {
            sb.append("- ").append(c.name()).append("  (id: ").append(c.uuid()).append(")  [")
                    .append(c.state().name());
            if ("EXTERNAL".equals(c.state().name()) && !c.controllerLabel().isEmpty()) {
                sb.append(" by ").append(c.controllerLabel());
            }
            sb.append(", ttl ").append(c.ttlRemainingTicks() / 20).append("s]");
            if (!c.alive()) {
                sb.append("  DEAD, respawns in ").append(c.respawnRemainingMs() / 1000).append('s');
            }
            if (!c.dimension().isEmpty()) sb.append("  @").append(c.dimension());
            if (c.hp() >= 0) sb.append("  HP ").append((int) c.hp()).append('/').append((int) c.maxHp());
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * {@code session} carries the caller's identity (see {@link McpSession#controllerId}/{@link
     * McpSession#agentLabel}) — attributing the lease to a real per-brain identity, rather than
     * one generic "mcp" controller, is what lets two competing agents (e.g. Claude Code and Codex,
     * each with their own configured agent + token, possibly several sessions each) show up
     * distinctly on the owner's console. The per-session {@link McpSession#putLeaseToken}/{@link
     * McpSession#leaseTokenFor} replace the Wave 3 integrator's server-wide stop-gap map.
     */
    private JsonObject handleControl(JsonObject args, boolean acquire, McpSession session) throws Exception {
        UUID target = resolveCompanion(args);
        if (target == null) {
            return content("no such companion — call list_companions to see valid names/ids", true);
        }
        if (acquire) {
            NumenActuator.AcquireResult result = NumenActuator.acquire(target, session.controllerId(),
                            session.agentLabel(), DEFAULT_LEASE_TTL_SECONDS)
                    .get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!result.ok()) {
                return content("could not acquire " + target
                        + (result.reason().isEmpty() ? "" : ": " + result.reason()), true);
            }
            session.putLeaseToken(target, result.sessionToken());
            return content("acquired " + target + " — its built-in brain is paused; you are driving it now", false);
        }
        String token = session.leaseTokenFor(target);
        boolean ok = NumenActuator.release(target, token).get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (ok) session.clearLeaseToken(target, token);   // only clear if it was still OUR token that got released
        return content(ok ? "released " + target + " — its built-in brain may act again"
                : "could not release " + target + " (you may not currently hold it)", !ok);
    }

    /**
     * W14 — the honest tool-call lifecycle: tracks the call on {@code session} so {@code
     * notifications/cancelled} can find it (item 2), schedules an elapsed-time progress heartbeat
     * when the caller asked for one (item 1), and — on a timeout — actually tells the body to stop
     * via {@link NumenActuator#cancel} instead of abandoning the future while the body keeps
     * working (item 3). {@code finally} always untracks and cancels the heartbeat, whichever way
     * the call ended, so nothing lingers in {@code session}'s map past its own lifetime.
     */
    private JsonObject handleToolInvoke(String toolName, JsonObject args, JsonElement id, McpSession session,
                                         JsonElement progressToken) throws Exception {
        UUID target = resolveCompanion(args);
        if (target == null) {
            return content("this tool needs a 'companion' argument (a name or id from list_companions)", true);
        }
        JsonObject toolArgs = args.deepCopy();
        toolArgs.remove("companion");
        String token = session.leaseTokenFor(target);
        java.util.concurrent.CompletableFuture<String> future =
                NumenActuator.invoke(target, toolName, toolArgs.toString(), token);
        String key = idKey(id);
        McpSession.InFlightCall call = new McpSession.InFlightCall(target, toolName, token, future, progressToken);
        session.trackInFlight(key, call);
        if (progressToken != null) {
            call.heartbeat = scheduler.scheduleAtFixedRate(() -> sendProgress(session, call),
                    PROGRESS_INTERVAL_SECONDS, PROGRESS_INTERVAL_SECONDS, TimeUnit.SECONDS);
        }
        try {
            String result = future.get(config.callTimeoutSeconds(), TimeUnit.SECONDS);
            return content(result, isErrorResult(result));
        } catch (TimeoutException te) {
            NumenActuator.cancel(target, token, "call timed out after " + config.callTimeoutSeconds() + "s");
            return content("action cancelled: '" + toolName + "' did not finish within "
                    + config.callTimeoutSeconds() + "s, so the body was told to stop executing it", true);
        } finally {
            if (call.heartbeat != null) call.heartbeat.cancel(false);
            session.untrackInFlight(key);
        }
    }

    /** {@code notifications/cancelled}: {@code params.requestId} names the original {@code
     *  tools/call}'s id. Looks it up on the SAME session that owns it (a cancel notification for
     *  someone else's call simply finds nothing — sessions cannot cancel each other's work), then
     *  routes through {@link NumenActuator#cancel}, whose own {@code failOutstanding} is what
     *  actually unblocks {@link #handleToolInvoke}'s {@code future.get(...)} on whichever thread is
     *  blocked in it. */
    private void handleCancelled(JsonObject req, McpSession session) {
        JsonObject params = req.has("params") && req.get("params").isJsonObject()
                ? req.getAsJsonObject("params") : new JsonObject();
        JsonElement ridEl = params.get("requestId");
        if (ridEl == null || ridEl.isJsonNull()) return;
        String key = idKey(ridEl);
        McpSession.InFlightCall call = session.untrackInFlight(key);
        if (call == null) return;   // already settled, or not this session's call — nothing to do
        if (call.heartbeat != null) call.heartbeat.cancel(false);
        NumenActuator.cancel(call.companion, call.sessionToken, "cancelled by caller (notifications/cancelled)");
    }

    /** Elapsed-time progress — see the class doc for why this is not true task-percentage
     *  progress. Self-cancelling: if the call already settled by the time this fires (a race with
     *  {@code finally}'s own cancel is inherent to a periodic task), it is a no-op. */
    private void sendProgress(McpSession session, McpSession.InFlightCall call) {
        if (call.future.isDone()) {
            if (call.heartbeat != null) call.heartbeat.cancel(false);
            return;
        }
        long elapsedSeconds = (System.currentTimeMillis() - call.startedAtMillis) / 1000;
        JsonObject params = new JsonObject();
        params.add("progressToken", call.progressToken);
        params.addProperty("progress", elapsedSeconds);
        params.addProperty("message", "still executing " + call.toolName + " on " + call.companion
                + " (" + elapsedSeconds + "s elapsed)...");
        notify(session, "notifications/progress", params);
    }

    /** JSON-RPC id (or a {@code notifications/cancelled} {@code requestId}) as a stable map key —
     *  {@link JsonElement#toString()} serialises a number and a quoted string distinctly and
     *  consistently, so the same logical id always produces the same key on both sides. */
    private static String idKey(JsonElement id) {
        return (id == null || id.isJsonNull()) ? "" : id.toString();
    }

    private boolean isErrorResult(String result) {
        try {
            JsonObject r = JsonParser.parseString(result).getAsJsonObject();
            return r.has("success") && !r.get("success").getAsBoolean();
        } catch (RuntimeException ignored) {
            return false; // non-JSON result — treat as plain text, not an error
        }
    }

    /** Resolve the {@code companion} argument (name or UUID) to a live companion's UUID, or null. */
    private UUID resolveCompanion(JsonObject args) throws Exception {
        if (!args.has("companion") || args.get("companion").isJsonNull()) return null;
        String raw = args.get("companion").getAsString().trim();
        if (raw.isEmpty()) return null;
        List<NumenActuator.Companion> list = NumenActuator.companions()
                .get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        // exact id match first
        for (NumenActuator.Companion c : list) {
            if (c.uuid().toString().equalsIgnoreCase(raw)) return c.uuid();
        }
        // then exact name, then case-insensitive name
        for (NumenActuator.Companion c : list) {
            if (c.name().equals(raw)) return c.uuid();
        }
        for (NumenActuator.Companion c : list) {
            if (c.name().equalsIgnoreCase(raw)) return c.uuid();
        }
        return null;
    }

    // ---- resources/* (W13) ----

    private JsonObject resourcesReadResponse(JsonObject req, JsonElement id) {
        JsonObject params = req.has("params") && req.get("params").isJsonObject()
                ? req.getAsJsonObject("params") : new JsonObject();
        String uri = params.has("uri") ? params.get("uri").getAsString() : "";
        try {
            McpResources.ReadResult result = McpResources.read(uri, config)
                    .get(RESOURCE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (result.error() != null) {
                return errorResponse(id, -32002, result.error());
            }
            JsonObject c = new JsonObject();
            c.addProperty("uri", uri);
            c.addProperty("mimeType", result.mimeType());
            c.addProperty("text", result.text());
            JsonArray arr = new JsonArray();
            arr.add(c);
            JsonObject out = new JsonObject();
            out.add("contents", arr);
            return okResponse(id, out);
        } catch (Exception ex) {
            return errorResponse(id, -32603, "resource read failed: " + ex.getMessage());
        }
    }

    private JsonObject resourcesSubscribeResponse(JsonObject req, JsonElement id, McpSession session, boolean subscribe) {
        JsonObject params = req.has("params") && req.get("params").isJsonObject()
                ? req.getAsJsonObject("params") : new JsonObject();
        String uri = params.has("uri") ? params.get("uri").getAsString() : "";
        if (subscribe) {
            if (!McpResources.isSubscribable(uri)) {
                return errorResponse(id, -32602, "not subscribable: " + uri);
            }
            session.subscribe(uri);
        } else {
            session.unsubscribe(uri);
        }
        return okResponse(id, new JsonObject());
    }

    // ---- prompts/* (W13) — one prompt per installed skill ----

    private JsonObject promptsListResponse(JsonElement id) {
        try {
            List<NumenActuator.SkillSummary> skills = NumenActuator.skills().get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            JsonArray arr = new JsonArray();
            for (NumenActuator.SkillSummary s : skills) {
                JsonObject o = new JsonObject();
                o.addProperty("name", s.name());
                o.addProperty("description", s.description());
                arr.add(o);
            }
            JsonObject result = new JsonObject();
            result.add("prompts", arr);
            return okResponse(id, result);
        } catch (Exception ex) {
            return errorResponse(id, -32603, "prompts/list failed: " + ex.getMessage());
        }
    }

    private JsonObject promptsGetResponse(JsonObject req, JsonElement id) {
        JsonObject params = req.has("params") && req.get("params").isJsonObject()
                ? req.getAsJsonObject("params") : new JsonObject();
        String name = params.has("name") ? params.get("name").getAsString() : "";
        try {
            List<NumenActuator.SkillSummary> skills = NumenActuator.skills().get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            for (NumenActuator.SkillSummary s : skills) {
                if (!s.name().equals(name)) continue;
                JsonObject textContent = new JsonObject();
                textContent.addProperty("type", "text");
                textContent.addProperty("text", s.content());
                JsonObject msg = new JsonObject();
                msg.addProperty("role", "user");
                msg.add("content", textContent);
                JsonArray messages = new JsonArray();
                messages.add(msg);
                JsonObject result = new JsonObject();
                result.addProperty("description", s.description());
                result.add("messages", messages);
                return okResponse(id, result);
            }
            return errorResponse(id, -32602, "no such skill/prompt: " + name);
        } catch (Exception ex) {
            return errorResponse(id, -32603, "prompts/get failed: " + ex.getMessage());
        }
    }

    // ---- push notifications (W11 item 6 helpers; W12's control/roster/lifecycle piggyback) ----

    private void notify(McpSession session, String method, JsonObject params) {
        JsonObject frame = new JsonObject();
        frame.addProperty("jsonrpc", "2.0");
        frame.addProperty("method", method);
        if (params != null) frame.add("params", params);
        session.writeFrame("event: message\ndata: " + gson.toJson(frame) + "\n\n");
    }

    private void broadcast(String method, JsonObject params) {
        for (McpSession s : sessions.values()) notify(s, method, params);
    }

    /**
     * Fired by {@link NumenActuator#subscribeControlChanges} on the CLIENT MAIN THREAD (see that
     * method's doc) — every ~20 ticks unconditionally, plus on every real transition. Must stay
     * cheap here: the actual diff-and-notify work (including the {@link NumenActuator#companions}
     * round trip, itself main-thread-bounced) completes back on the main thread too, so the
     * socket-write fan-out in {@link #diffAndNotify} is explicitly handed to {@link #executor} —
     * never performed inline — so a slow/blocked SSE consumer cannot stall the game loop.
     */
    private void onControlChanged() {
        NumenActuator.companions().thenAccept(this::diffAndNotify);
    }

    private void diffAndNotify(List<NumenActuator.Companion> fresh) {
        Map<UUID, NumenActuator.Companion> freshMap = new HashMap<>();
        for (NumenActuator.Companion c : fresh) freshMap.put(c.uuid(), c);

        List<Runnable> jobs = new ArrayList<>();
        for (NumenActuator.Companion c : fresh) {
            NumenActuator.Companion prev = lastSeen.get(c.uuid());
            if (prev == null) {
                jobs.add(() -> pushRoster(c.uuid(), c.name(), "added"));
                continue;
            }
            boolean stateChanged = !prev.state().name().equals(c.state().name())
                    || !prev.controllerLabel().equals(c.controllerLabel());
            if (stateChanged) {
                if ("EXTERNAL".equals(prev.state().name()) && !"EXTERNAL".equals(c.state().name())) {
                    jobs.add(() -> pushRevoked(c));
                }
                jobs.add(() -> pushControl(c));
            }
            if (prev.alive() != c.alive()) {
                jobs.add(() -> pushLifecycle(c));
            }
        }
        for (UUID gone : lastSeen.keySet()) {
            if (!freshMap.containsKey(gone)) {
                jobs.add(() -> pushRoster(gone, "", "removed"));
            }
        }
        lastSeen.keySet().retainAll(freshMap.keySet());
        lastSeen.putAll(freshMap);

        if (jobs.isEmpty() || executor == null) return;
        executor.execute(() -> jobs.forEach(Runnable::run));
    }

    private void pushControl(NumenActuator.Companion c) {
        JsonObject params = new JsonObject();
        params.addProperty("uuid", c.uuid().toString());
        params.addProperty("state", c.state().name());
        params.addProperty("controller_label", c.controllerLabel());
        params.addProperty("ttl_remaining_ticks", c.ttlRemainingTicks());
        broadcast("notifications/numen/control", params);
        notifyResourceUpdated("numen://companion/" + c.uuid() + "/control");
        notifyResourceUpdated("numen://companion/" + c.uuid() + "/status");
    }

    /** The row-37 case the design calls out as mattering most: tell the SPECIFIC session that just
     *  lost the lease, not just everyone in general — found by whichever sessions still believe
     *  they hold a (now-stale) token for this companion, and clears that stale belief afterward. */
    private void pushRevoked(NumenActuator.Companion c) {
        JsonObject params = new JsonObject();
        params.addProperty("uuid", c.uuid().toString());
        params.addProperty("state", c.state().name());
        params.addProperty("reason", "control returned to " + c.state().name().toLowerCase(java.util.Locale.ROOT));
        for (McpSession s : sessions.values()) {
            String staleToken = s.leaseTokenFor(c.uuid());
            if (staleToken.isEmpty()) continue;
            notify(s, "notifications/numen/control", params);
            s.clearLeaseToken(c.uuid(), staleToken);
        }
    }

    private void pushLifecycle(NumenActuator.Companion c) {
        JsonObject params = new JsonObject();
        params.addProperty("uuid", c.uuid().toString());
        params.addProperty("kind", c.alive() ? "respawned" : "died");
        params.addProperty("respawn_remaining_ms", c.respawnRemainingMs());
        broadcast("notifications/numen/lifecycle", params);
    }

    private void pushRoster(UUID uuid, String name, String kind) {
        JsonObject params = new JsonObject();
        params.addProperty("uuid", uuid.toString());
        params.addProperty("name", name);
        params.addProperty("kind", kind);
        broadcast("notifications/numen/roster", params);
        broadcast("notifications/resources/list_changed", null);
    }

    private void notifyResourceUpdated(String uri) {
        JsonObject params = new JsonObject();
        params.addProperty("uri", uri);
        for (McpSession s : sessions.values()) {
            if (s.isSubscribed(uri)) notify(s, "notifications/resources/updated", params);
        }
    }

    // ---- result envelopes ----

    private JsonObject content(String text, boolean isError) {
        JsonObject item = new JsonObject();
        item.addProperty("type", "text");
        item.addProperty("text", text);
        JsonArray arr = new JsonArray();
        arr.add(item);
        JsonObject result = new JsonObject();
        result.add("content", arr);
        result.addProperty("isError", isError);
        return result;
    }

    private JsonObject okResponse(JsonElement id, JsonObject result) {
        JsonObject r = new JsonObject();
        r.addProperty("jsonrpc", "2.0");
        r.add("id", id);
        r.add("result", result);
        return r;
    }

    private JsonObject errorResponse(JsonElement id, int code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject r = new JsonObject();
        r.addProperty("jsonrpc", "2.0");
        r.add("id", id == null ? JsonNull() : id);
        r.add("error", err);
        return r;
    }

    private static JsonElement JsonNull() {
        return com.google.gson.JsonNull.INSTANCE;
    }
}
