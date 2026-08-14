package com.dwinovo.numen.mcp;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.api.NumenActuator;
import com.dwinovo.numen.mcp.coordinator.Coordinator;
import com.dwinovo.numen.mcp.coordinator.CoordinatorTools;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
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
 *
 * <h2>Cluster coordinator</h2>
 * Companions acquired through this server form a cluster (see
 * {@code mcp/docs/cluster-coordinator-v1.md}): {@code broadcast} /
 * {@code whisper} / {@code trade_*} let the driving agents talk and trade.
 * Incoming messages are never pushed — they piggyback at the tail of the
 * recipient's next tool result inside an {@code <inbox>} block
 * (see {@link #content(String, boolean, UUID)}).
 */
public final class McpServer {

    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String SERVER_VERSION = "0.2.1";
    /** Roster / acquire / release are fast; only tool actions use the config timeout. */
    private static final int CONTROL_TIMEOUT_SECONDS = 10;

    /**
     * tools/call names answered quickly (≤10s client-thread hops) — the control lane.
     * Everything else (engine body tools, trade_give) can block for minutes and
     * therefore goes to the work lane, so a fleet deep in long tasks can never
     * starve pings, roster lookups, or cluster messaging.
     */
    private static final Set<String> LIGHT_TOOLS = Set.of(
            "list_companions", "acquire_companion", "release_companion",
            "broadcast", "whisper", "trade_invite", "trade_accept", "trade_decline", "trade_close");

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
            drive them in parallel. Modded blocks, items, and GUIs (Create, AE2, Mekanism) work natively.

            Cluster (multi-agent): companions acquired through this server form a cluster. Social tools: \
            broadcast (say something to every other agent), whisper (private message to one, by companion \
            name), and locked trading — trade_invite (stand within a few blocks) → the other agent answers \
            trade_accept / trade_decline → both sides trade_give items (repeatable) → trade_close. Incoming \
            messages and trade events NEVER interrupt you: they arrive appended to the tail of tool results \
            inside an <inbox> block — finish your current step, then answer with the same tools.""";

    private final McpConfig config;
    private final Coordinator coordinator;
    private final CoordinatorTools coordinatorTools;
    private final Gson gson = new Gson();
    private HttpServer http;
    private ExecutorService controlExec;
    private ExecutorService workExec;

    public McpServer(McpConfig config) {
        this.config = config;
        this.coordinator = new Coordinator(config.mailboxCapacity());
        this.coordinatorTools = new CoordinatorTools(coordinator, config);
    }

    public void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        http.createContext("/mcp", this::handle);
        controlExec = Executors.newFixedThreadPool(4, daemonFactory("numen-mcp-ctl"));
        workExec = Executors.newFixedThreadPool(16, daemonFactory("numen-mcp-work"));
        // Accept+parse lane: unbounded cached pool. A request lives here only long
        // enough to read and parse its JSON, then hops to a lane — it never performs
        // game work, so connections stay responsive even when every work thread is
        // parked inside a multi-minute body action.
        http.setExecutor(Executors.newCachedThreadPool(daemonFactory("numen-mcp-accept")));
        http.start();
    }

    public void stop() {
        if (http != null) http.stop(0);
        if (controlExec != null) controlExec.shutdownNow();
        if (workExec != null) workExec.shutdownNow();
    }

    private static ThreadFactory daemonFactory(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    // ---- HTTP layer ----

    private void handle(HttpExchange ex) {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                respond(ex, 405, "");
                ex.close();
                return;
            }
            if (!authorized(ex)) {
                respond(ex, 401, "");
                ex.close();
                return;
            }
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(body);
            } catch (RuntimeException parseErr) {
                respondJson(ex, errorResponse(null, -32700, "parse error: " + parseErr.getMessage()));
                ex.close();
                return;
            }
            // Hand off to the right lane — serve() owns respond + close from here on.
            laneFor(parsed).execute(() -> serve(ex, parsed));
        } catch (RuntimeException | IOException fatal) {
            Constants.LOG.warn("[numen-mcp] request failed: {}", fatal.toString());
            try { respond(ex, 500, ""); } catch (IOException ignored) {}
            try { ex.close(); } catch (RuntimeException ignored) {}
        }
    }

    /** Lane stage: dispatch the request, write the response, close the exchange. */
    private void serve(HttpExchange ex, JsonElement parsed) {
        try {
            if (parsed.isJsonArray()) {   // JSON-RPC batch
                JsonArray out = new JsonArray();
                for (JsonElement el : parsed.getAsJsonArray()) {
                    JsonObject r = dispatch(el.getAsJsonObject());
                    if (r != null) out.add(r);
                }
                if (out.isEmpty()) respond(ex, 202, "");
                else respondJson(ex, out);
            } else {
                JsonObject r = dispatch(parsed.getAsJsonObject());
                if (r == null) respond(ex, 202, "");   // notification: no response
                else respondJson(ex, r);
            }
        } catch (RuntimeException | IOException fatal) {
            Constants.LOG.warn("[numen-mcp] request failed: {}", fatal.toString());
            try { respond(ex, 500, ""); } catch (IOException ignored) {}
        } finally {
            try { ex.close(); } catch (RuntimeException ignored) {}
        }
    }

    /**
     * Pick the lane for a parsed request. Control lane: handshakes, pings, tool
     * listing, roster/control tools, cluster messaging and trade bookkeeping —
     * everything answered in ≤10s. Work lane: engine body-tool invocations and
     * trade_give, which may block for minutes. Batches may mix heavy and light
     * calls, so they take the work lane.
     */
    private ExecutorService laneFor(JsonElement parsed) {
        if (!parsed.isJsonObject()) return workExec;   // malformed → dispatch errors out, either lane
        JsonObject req = parsed.getAsJsonObject();
        String method = req.has("method") && req.get("method").isJsonPrimitive()
                ? req.get("method").getAsString() : "";
        if (!"tools/call".equals(method)) return controlExec;
        JsonObject params = req.has("params") && req.get("params").isJsonObject()
                ? req.getAsJsonObject("params") : new JsonObject();
        String tool = params.has("name") && params.get("name").isJsonPrimitive()
                ? params.get("name").getAsString() : "";
        return LIGHT_TOOLS.contains(tool) ? controlExec : workExec;
    }

    private boolean authorized(HttpExchange ex) {
        if (config.token().isBlank()) return true;
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.equals("Bearer " + config.token())) return true;
        String query = ex.getRequestURI().getQuery();
        return query != null && query.contains("token=" + config.token());
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
    private JsonObject dispatch(JsonObject req) {
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
                case "tools/call" -> okResponse(id, toolsCallResult(req));
                default -> errorResponse(id, -32601, "method not found: " + method);
            };
        } catch (RuntimeException ex) {
            return errorResponse(id, -32603, "internal error: " + ex.getMessage());
        }
    }

    private JsonObject initializeResult(JsonObject req) {
        // We speak exactly one protocol version. Per MCP, a server that does not
        // support the client's version answers with one it DOES support — never
        // echo an unknown version back.
        String clientProto = PROTOCOL_VERSION;
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

        if (config.coordinatorEnabled()) {
            for (JsonObject def : coordinatorTools.defs()) tools.add(def);
        }

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

    private JsonObject toolsCallResult(JsonObject req) {
        JsonObject params = req.has("params") && req.get("params").isJsonObject()
                ? req.getAsJsonObject("params") : new JsonObject();
        String name = params.has("name") ? params.get("name").getAsString() : "";
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();

        try {
            return switch (name) {
                case "list_companions" -> content(listCompanions(), false);
                case "acquire_companion" -> handleControl(args, true);
                case "release_companion" -> handleControl(args, false);
                case "broadcast", "whisper", "trade_invite", "trade_accept", "trade_decline",
                     "trade_give", "trade_close" -> handleCluster(name, args);
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
        if (config.coordinatorEnabled()) {
            List<String> roster = coordinator.rosterLines();
            sb.append("\nCluster participants (acquired & drivable — valid broadcast / whisper / trade partners): ");
            sb.append(roster.isEmpty() ? "none yet" : String.join(", ", roster));
        }
        return sb.toString().stripTrailing();
    }

    private JsonObject handleCluster(String name, JsonObject args) {
        if (!config.coordinatorEnabled()) {
            return content("the cluster coordinator is disabled on this server (coordinator_enabled)", true);
        }
        CoordinatorTools.Reply r = coordinatorTools.call(name, args, this::resolveCompanion);
        return content(r.text(), r.error(), r.inboxOf());
    }

    private JsonObject handleControl(JsonObject args, boolean acquire) throws Exception {
        UUID target = resolveCompanion(args);
        if (target == null) {
            return content("no such companion — call list_companions to see valid names/ids", true);
        }
        boolean ok = (acquire
                ? NumenActuator.acquire(target)
                : NumenActuator.release(target))
                .get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (acquire) {
            if (ok && config.coordinatorEnabled()) {
                coordinator.join(target, companionName(target));
            }
            String text = ok
                    ? "acquired " + target + " — its built-in brain is paused; you are driving it now"
                            + (config.coordinatorEnabled()
                                    ? " You also joined the cluster: broadcast / whisper / trade_* are yours." : "")
                    : "could not acquire " + target;
            return content(text, !ok, ok && config.coordinatorEnabled() ? target : null);
        }
        if (config.coordinatorEnabled()) {
            coordinator.leave(target, config.inviteTimeoutSeconds());
        }
        return content(ok ? "released " + target + " — its built-in brain may act again"
                : "could not release " + target, !ok);
    }

    /** Display name of a live companion (falls back to the uuid string). */
    private String companionName(UUID target) throws Exception {
        for (NumenActuator.Companion c : NumenActuator.companions()
                .get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            if (c.uuid().equals(target)) return c.name();
        }
        return target.toString();
    }

    private JsonObject handleToolInvoke(String toolName, JsonObject args) throws Exception {
        // hidden_tools gates the CALL as well as the listing (case-tolerant, mirroring
        // ToolRegistry.resolve) — the built-in brain's bookkeeping is not drivable.
        if (config.isHidden(toolName)
                || config.isHidden(toolName.toLowerCase(java.util.Locale.ROOT))) {
            return content("tool not available to external drivers: " + toolName, true);
        }
        UUID target = resolveCompanion(args);
        if (target == null) {
            return content("this tool needs a 'companion' argument (a name or id from list_companions)", true);
        }
        JsonObject toolArgs = args.deepCopy();
        toolArgs.remove("companion");
        String result = NumenActuator.invoke(target, toolName, toolArgs.toString())
                .get(config.callTimeoutSeconds(), TimeUnit.SECONDS);
        boolean isError = false;
        try {
            JsonObject r = JsonParser.parseString(result).getAsJsonObject();
            isError = r.has("success") && !r.get("success").getAsBoolean();
        } catch (RuntimeException ignored) {
            // non-JSON result — treat as plain text, not an error
        }
        return content(result, isError, config.coordinatorEnabled() ? target : null);
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
        return content(text, isError, null);
    }

    /**
     * The single result-envelope exit point — and therefore the cluster
     * coordinator's delivery ride: when {@code inboxOf} names a participant,
     * anything parked in their mailbox rides along as a <strong>separate second
     * text element</strong> in the content array (mailbox cleared on delivery).
     * The first element stays byte-pure — a body-tool result remains parseable
     * TaskResult JSON. Messages never push; they surface strictly between the
     * recipient's own tool calls.
     */
    private JsonObject content(String text, boolean isError, UUID inboxOf) {
        JsonArray arr = new JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("type", "text");
        item.addProperty("text", text);
        arr.add(item);
        if (inboxOf != null) {
            String inbox = coordinator.drainInbox(inboxOf);
            if (!inbox.isEmpty()) {
                JsonObject box = new JsonObject();
                box.addProperty("type", "text");
                box.addProperty("text", inbox);
                arr.add(box);
            }
        }
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
