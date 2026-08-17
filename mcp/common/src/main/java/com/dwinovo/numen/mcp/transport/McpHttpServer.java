package com.dwinovo.numen.mcp.transport;

import com.dwinovo.numen.mcp.Constants;
import com.dwinovo.numen.mcp.McpConfig;
import com.dwinovo.numen.mcp.protocol.JsonRpc;
import com.dwinovo.numen.mcp.tools.ToolSurface;
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
import java.util.concurrent.Executors;

/**
 * Streamable HTTP transport for the MCP bridge — {@code POST} / {@code GET} / {@code DELETE} on
 * {@code /mcp}, with sessions.
 *
 * <p>Replaces the previous POST-only server. That server could not be extended into this: with no
 * GET handler there was no server-to-client channel, so push was not "unimplemented" but
 * unrepresentable; and with no session object there was nowhere to hang per-agent lease state.
 *
 * <table>
 *   <caption>Verb semantics</caption>
 *   <tr><td>{@code POST}</td><td>JSON-RPC request or batch. {@code initialize} mints a session and
 *       returns its id in the {@code Mcp-Session-Id} response header.</td></tr>
 *   <tr><td>{@code GET}</td><td>Opens the SSE stream for server-to-client notifications. Blocks
 *       the calling worker for the life of the stream.</td></tr>
 *   <tr><td>{@code DELETE}</td><td>Ends the session and releases every lease it held.</td></tr>
 * </table>
 *
 * <h2>Threading</h2>
 * A fixed pool serves requests. A {@code GET} parks one worker for the stream's lifetime, so the
 * pool is sized for (agents × 2) plus headroom rather than request concurrency. Nothing here
 * touches game state directly — every game interaction goes through {@code NumenActuator}, which
 * marshals onto the client main thread.
 */
public final class McpHttpServer {

    /** MCP revision this bridge implements. Echoed to a client that does not pin one. */
    public static final String PROTOCOL_VERSION = "2025-06-18";
    public static final String SERVER_VERSION = "0.2.0";

    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private static final String INSTRUCTIONS = """
            You are driving live Minecraft companions in someone's real game world.

            Start with list_companions, then acquire_companion before any action tool — the
            companion's own built-in AI is paused while you hold it, so exactly one brain drives a
            body at a time. Release it when you are done. If an acquire is refused, another agent
            holds that body; the refusal names them.

            The server is the authority on control. A tool call made without a live lease is
            rejected, so re-acquire rather than assuming you still hold a companion after an error.
            """;

    private final McpConfig config;
    private final McpSessions sessions;
    private final ToolSurface tools;
    private final Gson gson = new Gson();

    private HttpServer http;

    public McpHttpServer(McpConfig config) {
        this.config = config;
        this.sessions = new McpSessions(config);
        this.tools = new ToolSurface(config);
    }

    /** Live sessions — the journal pump (S5) fans notifications out through this. */
    public McpSessions sessions() {
        return sessions;
    }

    public void start() throws IOException {
        if (config.unguarded()) {
            // Startup-time refusal, backstopping the per-request check: a network-visible bind with
            // a blank token would put a Minecraft account under unauthenticated remote control.
            Constants.LOG.error("[numen-mcp] refusing to bind {}:{} — LAN-exposed with a blank agent "
                    + "token. Set a token for every agent in mcp_server.json.",
                    config.host(), config.port());
            return;
        }
        http = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        http.createContext("/mcp", this::handle);
        http.setExecutor(Executors.newFixedThreadPool(12, r -> {
            Thread t = new Thread(r, "numen-mcp-http");
            t.setDaemon(true);
            return t;
        }));
        http.start();
        Constants.LOG.info("[numen-mcp] listening on {}:{}/mcp (protocol {}, auth {})",
                config.host(), config.port(), PROTOCOL_VERSION,
                config.lanExposed() ? "required" : "loopback-open");
    }

    public void stop() {
        sessions.shutdown();
        if (http != null) {
            http.stop(0);
            http = null;
        }
    }

    // ============================================================ request routing

    private void handle(HttpExchange ex) {
        boolean streaming = false;
        try {
            if (!authorized(ex)) {
                respond(ex, 401, "");
                return;
            }
            McpConfig.Agent agent = config.agentForToken(presentedToken(ex));
            String method = ex.getRequestMethod();
            String sessionId = ex.getRequestHeaders().getFirst(SESSION_HEADER);

            switch (method.toUpperCase()) {
                case "POST" -> handlePost(ex, agent, sessionId);
                case "GET" -> {
                    streaming = handleGet(ex, agent, sessionId);
                }
                case "DELETE" -> {
                    McpSession s = sessions.resolve(sessionId, agent);
                    if (s != null) sessions.close(s, "client DELETE");
                    respond(ex, 204, "");
                }
                default -> respond(ex, 405, "");
            }
        } catch (RuntimeException | IOException fatal) {
            Constants.LOG.warn("[numen-mcp] request failed: {}", fatal.toString());
            try { respond(ex, 500, ""); } catch (IOException ignored) { }
        } finally {
            // A streaming GET owns its exchange until the peer disconnects; closing it here would
            // tear down the SSE stream the instant it was created.
            if (!streaming) ex.close();
        }
    }

    private void handlePost(HttpExchange ex, McpConfig.Agent agent, String sessionId)
            throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body);
        } catch (RuntimeException bad) {
            respondJson(ex, JsonRpc.error(null, JsonRpc.PARSE_ERROR,
                    "parse error: " + bad.getMessage()), null);
            return;
        }

        // initialize is the only method allowed to arrive without a session; it creates one.
        String mintedId = null;
        McpSession session = sessions.resolve(sessionId, agent);
        if (session == null) {
            session = sessions.open(agent);
            mintedId = session.sessionId();
        }

        if (parsed.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement el : parsed.getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                JsonObject r = dispatch(el.getAsJsonObject(), session);
                if (r != null) out.add(r);
            }
            if (out.isEmpty()) respond(ex, 202, "");
            else respondJson(ex, out, mintedId);
        } else if (parsed.isJsonObject()) {
            JsonObject r = dispatch(parsed.getAsJsonObject(), session);
            if (r == null) respond(ex, 202, "");        // notification: no reply
            else respondJson(ex, r, mintedId);
        } else {
            respondJson(ex, JsonRpc.error(null, JsonRpc.INVALID_REQUEST,
                    "expected a JSON object or array"), null);
        }
    }

    /**
     * Open the SSE stream. Returns true when the exchange has been handed to the stream and must
     * NOT be closed by the caller.
     */
    private boolean handleGet(HttpExchange ex, McpConfig.Agent agent, String sessionId)
            throws IOException {
        McpSession session = sessions.resolve(sessionId, agent);
        if (session == null) {
            respond(ex, 404, "");
            return false;
        }
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.getResponseHeaders().add("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);   // 0 = chunked, stream stays open

        OutputStream out = ex.getResponseBody();
        SseSink sink = new SseSink(out);
        session.attach(sink);
        Constants.LOG.info("[numen-mcp] SSE attached for session {}", session.sessionId());

        // Park this worker on the stream. Keep-alives double as liveness detection: when the peer
        // vanishes the write fails, the sink breaks, and we clean up. Without this loop the
        // exchange would close immediately and the stream would never exist.
        try {
            while (!sink.isBroken()) {
                Thread.sleep(15_000L);
                sink.keepAlive();
                session.touch();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            sink.close();
            ex.close();
            Constants.LOG.info("[numen-mcp] SSE detached for session {}", session.sessionId());
        }
        return true;
    }

    // ============================================================ JSON-RPC

    /** @return the response, or null for a notification (no id → no reply). */
    private JsonObject dispatch(JsonObject req, McpSession session) {
        JsonElement id = req.get("id");
        String method = JsonRpc.str(req, "method", "");
        if (id == null || id.isJsonNull()) return null;

        try {
            return switch (method) {
                case "initialize" -> JsonRpc.ok(id, initializeResult(req, session));
                case "ping" -> JsonRpc.ok(id, new JsonObject());
                case "tools/list" -> JsonRpc.ok(id, tools.listResult());
                case "tools/call" -> JsonRpc.ok(id, tools.callResult(req, session));
                default -> JsonRpc.error(id, JsonRpc.METHOD_NOT_FOUND, "method not found: " + method);
            };
        } catch (RuntimeException ex) {
            return JsonRpc.error(id, JsonRpc.INTERNAL_ERROR, "internal error: " + ex.getMessage());
        }
    }

    private JsonObject initializeResult(JsonObject req, McpSession session) {
        JsonObject params = JsonRpc.obj(req, "params");
        String clientProto = JsonRpc.str(params, "protocolVersion", PROTOCOL_VERSION);

        // The client's own name becomes the controller label the owner sees on their console,
        // which is far more use than a configured alias when several agents share one credential.
        JsonObject clientInfo = JsonRpc.obj(params, "clientInfo");
        String clientName = JsonRpc.str(clientInfo, "name", "");
        if (!clientName.isBlank()) {
            Constants.LOG.info("[numen-mcp] session {} identified as '{}'",
                    session.sessionId(), clientName);
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

    // ============================================================ auth + IO

    /**
     * Loopback binds stay frictionless; any network-visible bind requires a token that resolves by
     * equality to a configured agent. A blank token never means open access on a network — see
     * {@link McpConfig#unguarded} for the startup refusal that backstops this.
     */
    private boolean authorized(HttpExchange ex) {
        if (!config.lanExposed()) return true;
        return config.agentForToken(presentedToken(ex)) != null;
    }

    /** Authorization header first, then the query string's {@code token=} — equality only. */
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
                return value;
            }
        }
        return null;
    }

    private void respondJson(HttpExchange ex, JsonElement json, String mintedSessionId)
            throws IOException {
        byte[] bytes = gson.toJson(json).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        if (mintedSessionId != null) {
            ex.getResponseHeaders().add(SESSION_HEADER, mintedSessionId);
        }
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
}
