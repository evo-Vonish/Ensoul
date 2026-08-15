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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A minimal MCP (Model Context Protocol) server, hand-rolled as JSON-RPC 2.0
 * over the JDK's built-in HTTP server — no third-party MCP SDK, no Reactor. It
 * implements exactly the surface an external agent needs to drive companions:
 * {@code initialize}, {@code tools/list}, {@code tools/call}, {@code ping}, and
 * the {@code notifications/initialized} handshake.
 *
 * <h2>Transport</h2>
 * Binds a loopback HTTP endpoint at {@code /mcp}. Claude Desktop can't dial an
 * HTTP MCP server directly, so the user points it at this endpoint through the
 * {@code mcp-remote} stdio bridge; every JSON-RPC frame arrives here as an HTTP
 * POST and its response goes straight back in the HTTP body.
 *
 * <h2>Tool surface</h2>
 * Every engine tool (from {@link ToolRegistry}, minus the config's hidden set)
 * is advertised with an extra {@code companion} argument, and calls route to
 * {@link NumenActuator#invoke}. Three management tools — {@code list_companions},
 * {@code acquire_companion}, {@code release_companion} — wrap the actuator's
 * roster and control methods. Since every call is addressed to a companion and
 * each body runs its tasks independently, an agent can acquire several
 * companions and drive them in parallel.
 */
public final class McpServer {

    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String SERVER_VERSION = "0.1.0";
    /** Roster / acquire / release are fast; only tool actions use the config timeout. */
    private static final int CONTROL_TIMEOUT_SECONDS = 10;
    /**
     * Lease TTL requested on every {@code acquire_companion} — the server clamps it (60s-20min,
     * see {@code ControlRegistry}) and renews it on every authorized call, so this is only a "how
     * long before an idle caller must be considered gone" budget, not a hard ceiling on a call in
     * progress. 5 minutes taken as a reasonable default; the design doc's openQuestions still
     * flags the right value for the filmed-competition format as undecided (W10).
     */
    private static final int DEFAULT_LEASE_TTL_SECONDS = 300;

    /**
     * Sent to the connecting agent in the {@code initialize} handshake (MCP's
     * {@code instructions} field) — what Numen is and how to drive it, so any
     * client gets the essentials without a separately-installed skill.
     */
    private static final String INSTRUCTIONS = """
            Numen companions are AI-controlled, player-like characters inside a live Minecraft game. \
            Through this server you take control of a companion's body and play the game as it — perceive, \
            move, mine, build, craft, fight. You are the brain; the companion is your hands and eyes, and \
            its own built-in AI steps aside while you drive.

            Loop: (1) list_companions to see who is live; (2) acquire_companion (by name or id) to take \
            control — this pauses its built-in AI and frees its body; (3) perceive with get_self_status / \
            scan_blocks / scan_nearby_entities, then act with move_to / auto_mine / place_block / craft / \
            equip_item / hunt / etc.; (4) release_companion when done. Every tool takes a 'companion' \
            argument, so each call targets one companion.

            Rules: survival mode — the tools do only what a real player can (mine to get stone; there is no \
            give or setblock). You are blind between calls, so perceive before and after acting. Action \
            tools return only when the task finishes or times out. You can acquire several companions and \
            drive them in parallel. Modded blocks, items, and GUIs (Create, AE2, Mekanism) work natively.""";

    private final McpConfig config;
    private final Gson gson = new Gson();
    private HttpServer http;
    /**
     * STOP-GAP (pending W10's {@code McpSession}): the lease token an {@code acquire_companion}
     * most recently won for a companion, keyed by companion — NOT keyed by MCP session/connection.
     * The mutual-exclusion invariant already guarantees at most one holder per companion at a
     * time, so "the current token for this companion" is a sound proxy for "this bridge process's
     * belief about who holds it" until real per-session token scoping lands (session TTL sweeper,
     * release-on-disconnect, multiple concurrent sessions inside one bridge). Written only on a
     * successful acquire; read (never removed) by every subsequent {@code tools/call} so a stale
     * or absent entry surfaces as {@link NumenActuator#invoke}'s own "you do not hold this
     * companion" refusal rather than a NPE here.
     */
    private final Map<UUID, String> leaseTokens = new ConcurrentHashMap<>();

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
        http.setExecutor(Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "numen-mcp-http");
            t.setDaemon(true);
            return t;
        }));
        http.start();
    }

    public void stop() {
        if (http != null) http.stop(0);
    }

    // ---- HTTP layer ----

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                respond(ex, 405, "");
                return;
            }
            if (!authorized(ex)) {
                respond(ex, 401, "");
                return;
            }
            // Resolved once per request and threaded through to acquire_companion so the lease
            // is attributed to the connecting brain's own identity (not a single shared "mcp"
            // controller id) -- this is what lets the owner's console tell two competing agents
            // apart. Null on a loopback bind with no token presented; call sites fall back to a
            // generic identity in that case (see handleControl).
            McpConfig.Agent agent = resolveAgent(ex);
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(body);
            } catch (RuntimeException parseErr) {
                respondJson(ex, errorResponse(null, -32700, "parse error: " + parseErr.getMessage()));
                return;
            }

            if (parsed.isJsonArray()) {   // JSON-RPC batch
                JsonArray out = new JsonArray();
                for (JsonElement el : parsed.getAsJsonArray()) {
                    JsonObject r = dispatch(el.getAsJsonObject(), agent);
                    if (r != null) out.add(r);
                }
                if (out.isEmpty()) respond(ex, 202, "");
                else respondJson(ex, out);
            } else {
                JsonObject r = dispatch(parsed.getAsJsonObject(), agent);
                if (r == null) respond(ex, 202, "");   // notification: no response
                else respondJson(ex, r);
            }
        } catch (RuntimeException fatal) {
            Constants.LOG.warn("[numen-mcp] request failed: {}", fatal.toString());
            try { respond(ex, 500, ""); } catch (IOException ignored) {}
        } finally {
            ex.close();
        }
    }

    /**
     * Loopback keeps the original zero-friction behaviour in this pass: the
     * per-agent schema is migrated and a token is minted regardless of {@code
     * host}, but enforcement only turns on once the bind is network-visible, so
     * an existing local setup is not broken by the migration itself. The instant
     * {@code host} is LAN-exposed, every request must resolve to a real per-agent
     * token: see {@link McpConfig#agentForToken} for the equality (never
     * substring) comparison, and {@link McpConfig#unguarded} for the startup-time
     * refusal that backstops this at the config level.
     */
    private boolean authorized(HttpExchange ex) {
        if (!config.lanExposed()) return true;
        return resolveAgent(ex) != null;
    }

    /**
     * The agent that owns the bearer/query token on this request, or null if none
     * was presented or it matches no configured agent. Exposed beyond {@link
     * #authorized} because a later wave attributes each MCP session to the agent
     * that opened it (at {@code initialize} time) using this same resolution.
     */
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

    private void respondJson(HttpExchange ex, JsonElement json) throws IOException {
        byte[] bytes = gson.toJson(json).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
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

    // ---- JSON-RPC dispatch ----

    /** @return the response object, or null for a notification (no id → no reply). */
    private JsonObject dispatch(JsonObject req, McpConfig.Agent agent) {
        JsonElement id = req.get("id");
        String method = req.has("method") ? req.get("method").getAsString() : "";

        // Notifications carry no id and expect no response.
        if (id == null || id.isJsonNull()) {
            return null;
        }

        try {
            return switch (method) {
                case "initialize" -> okResponse(id, initializeResult(req));
                case "ping" -> okResponse(id, new JsonObject());
                case "tools/list" -> okResponse(id, toolsListResult());
                case "tools/call" -> okResponse(id, toolsCallResult(req, agent));
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
            if (p.has("protocolVersion")) clientProto = p.get("protocolVersion").getAsString();
        }
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", clientProto);
        JsonObject caps = new JsonObject();
        caps.add("tools", new JsonObject());
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
                "List the owner's live Minecraft companions (name + id). Call this first to see who you can drive.",
                objectSchema(null, false)));
        tools.add(toolDef("acquire_companion",
                "Take control of a companion so you (not its built-in AI) drive it. Pauses its built-in brain and "
                        + "frees its body. Do this before calling any action tool on it.",
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

    private JsonObject toolsCallResult(JsonObject req, McpConfig.Agent agent) {
        JsonObject params = req.has("params") && req.get("params").isJsonObject()
                ? req.getAsJsonObject("params") : new JsonObject();
        String name = params.has("name") ? params.get("name").getAsString() : "";
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();

        try {
            return switch (name) {
                case "list_companions" -> content(listCompanions(), false);
                case "acquire_companion" -> handleControl(args, true, agent);
                case "release_companion" -> handleControl(args, false, agent);
                default -> handleToolInvoke(name, args);
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
            sb.append("- ").append(c.name()).append("  (id: ").append(c.uuid()).append(")\n");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * {@code agent} is the caller resolved from the request's own bearer/query token (see {@link
     * #resolveAgent}) — attributing the lease to a real per-brain identity, rather than one
     * generic "mcp" controller, is what lets two competing agents (e.g. Claude Code and Codex,
     * each with their own configured agent + token) show up distinctly on the owner's console.
     * Falls back to a generic identity only when nothing was presented (a bare loopback bind with
     * no token configured) — matches the pre-per-agent-auth default of allowing that connection
     * through at all.
     */
    private JsonObject handleControl(JsonObject args, boolean acquire, McpConfig.Agent agent) throws Exception {
        UUID target = resolveCompanion(args);
        if (target == null) {
            return content("no such companion — call list_companions to see valid names/ids", true);
        }
        if (acquire) {
            String controllerId = agent != null ? agent.id() : "mcp";
            String label = agent != null ? agent.label() : "MCP (unauthenticated loopback)";
            NumenActuator.AcquireResult result = NumenActuator.acquire(target, controllerId, label,
                            DEFAULT_LEASE_TTL_SECONDS)
                    .get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!result.ok()) {
                return content("could not acquire " + target
                        + (result.reason().isEmpty() ? "" : ": " + result.reason()), true);
            }
            leaseTokens.put(target, result.sessionToken());
            return content("acquired " + target + " — its built-in brain is paused; you are driving it now", false);
        }
        // Release: hand back whatever token this bridge process last won for the companion (see
        // the leaseTokens field doc) rather than trust the caller to have kept track of it.
        String token = leaseTokens.getOrDefault(target, "");
        boolean ok = NumenActuator.release(target, token).get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (ok) leaseTokens.remove(target, token);   // only clear if it was still OUR token that got released
        return content(ok ? "released " + target + " — its built-in brain may act again"
                : "could not release " + target + " (you may not currently hold it)", !ok);
    }

    private JsonObject handleToolInvoke(String toolName, JsonObject args) throws Exception {
        UUID target = resolveCompanion(args);
        if (target == null) {
            return content("this tool needs a 'companion' argument (a name or id from list_companions)", true);
        }
        JsonObject toolArgs = args.deepCopy();
        toolArgs.remove("companion");
        String token = leaseTokens.getOrDefault(target, "");
        String result = NumenActuator.invoke(target, toolName, toolArgs.toString(), token)
                .get(config.callTimeoutSeconds(), TimeUnit.SECONDS);
        boolean isError = false;
        try {
            JsonObject r = JsonParser.parseString(result).getAsJsonObject();
            isError = r.has("success") && !r.get("success").getAsBoolean();
        } catch (RuntimeException ignored) {
            // non-JSON result — treat as plain text, not an error
        }
        return content(result, isError);
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
