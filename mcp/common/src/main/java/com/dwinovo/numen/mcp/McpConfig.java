package com.dwinovo.numen.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Server config at {@code config/numen/mcp_server.json}. Plain Gson-over-file,
 * created with defaults on first launch.
 *
 * <ul>
 *   <li>{@code enabled} — master switch (default true);</li>
 *   <li>{@code host}/{@code port} — where the MCP HTTP endpoint binds. Loopback
 *       by default; an external agent reaches it through the {@code mcp-remote}
 *       stdio bridge in {@code claude_desktop_config.json};</li>
 *   <li>{@code agents} — one credential per connecting brain (Claude Code, Codex,
 *       Kimi Code, ...), each with a stable {@code id}, a human-readable
 *       {@code label} for logs/UI, and its own bearer {@code token}. A single
 *       shared secret cannot tell two callers apart, and the control-authority
 *       invariant needs to attribute a lease to a specific controller — so every
 *       agent gets its own credential rather than the server having one;</li>
 *   <li>{@code session_ttl_seconds} — how long an MCP session may sit idle before
 *       a later wave's sweeper reaps it and releases whatever it held (default
 *       120s). Config-only in this pass: nothing reads it yet, the session
 *       object it will govern lands separately;</li>
 *   <li>{@code call_timeout_seconds} — how long one {@code tools/call} waits for a
 *       body action to finish before reporting a timeout;</li>
 *   <li>{@code hidden_tools} — engine tools NOT exposed to the external agent
 *       (agent-internal bookkeeping the external brain has no business calling);</li>
 *   <li>{@code expose_conversation}/{@code expose_usage} — off by default; a later
 *       wave's read-only resources surface the built-in brain's private chat and
 *       token usage only when an operator opts in.</li>
 * </ul>
 *
 * <h2>Loopback vs. network-visible binds</h2>
 * A blank agent token is only ever safe on a loopback bind (only same-machine
 * processes can dial 127.0.0.1/localhost). The moment {@code host} is anything
 * else, a blank token would mean "any machine on the LAN drives this Minecraft
 * account unauthenticated" — so {@link #unguarded()} refuses to let the server
 * start in that combination rather than silently serving open traffic. See
 * {@link McpServer#authorized}.
 */
public record McpConfig(
        boolean enabled,
        String host,
        int port,
        List<Agent> agents,
        int sessionTtlSeconds,
        int callTimeoutSeconds,
        List<String> hiddenTools,
        boolean exposeConversation,
        boolean exposeUsage) {

    /** One credential per connecting brain. {@code token} may be blank — see the class javadoc. */
    public record Agent(String id, String label, String token) {}

    /**
     * Tools the built-in brain manages for itself — never handed to an external driver.
     *
     * <p>W13 removed {@code load_skill}: skills are now reachable two ways — as a {@code
     * prompts/get} result (the skill body, structured MCP-native) via {@link McpResources}, AND
     * still as this ordinary tool call for a client that only implements {@code tools/call}. Both
     * paths read the same {@link com.dwinovo.numen.agent.skill.SkillRegistry}, so hiding the tool
     * would only have made the second, more-compatible path unreachable for no benefit.
     */
    private static final List<String> DEFAULT_HIDDEN = List.of("todowrite");
    private static final int DEFAULT_SESSION_TTL_SECONDS = 120;
    private static final int DEFAULT_CALL_TIMEOUT_SECONDS = 300;
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 8765;
    private static final SecureRandom TOKEN_RNG = new SecureRandom();

    /**
     * A fresh, unguessable bearer token: 18 random bytes (SecureRandom), URL-safe
     * Base64 without padding, prefixed {@code "numen-"} so a token is recognizable
     * at a glance in logs/config. URL-safe specifically because the token is
     * allowed to ride in a {@code ?token=} query parameter for clients that can't
     * set an Authorization header.
     */
    public static String mintToken() {
        byte[] raw = new byte[18];
        TOKEN_RNG.nextBytes(raw);
        return "numen-" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** True once {@code host} is anything other than 127.0.0.1 or localhost (case-insensitive). */
    public boolean lanExposed() {
        return !DEFAULT_HOST.equalsIgnoreCase(host) && !"localhost".equalsIgnoreCase(host);
    }

    /**
     * True when this config would let a network-visible bind serve traffic with no
     * usable credential at all — a LAN-exposed host plus at least one agent whose
     * token is blank. {@link McpServer#start} refuses to bind while this holds.
     */
    public boolean unguarded() {
        if (!lanExposed()) return false;
        for (Agent a : agents) {
            if (a.token().isBlank()) return true;
        }
        return false;
    }

    /** Resolves a presented bearer token to the agent that owns it. A blank token never matches. */
    public Agent agentForToken(String token) {
        if (token == null || token.isBlank()) return null;
        for (Agent a : agents) {
            if (!a.token().isBlank() && a.token().equals(token)) return a;
        }
        return null;
    }

    public boolean isHidden(String toolName) {
        return hiddenTools.contains(toolName);
    }

    public static McpConfig load(Path file) {
        if (!Files.isRegularFile(file)) {
            McpConfig def = new McpConfig(true, DEFAULT_HOST, DEFAULT_PORT,
                    List.of(new Agent("default", "Default", mintToken())),
                    DEFAULT_SESSION_TTL_SECONDS, DEFAULT_CALL_TIMEOUT_SECONDS, DEFAULT_HIDDEN, false, false);
            writeJson(file, toJson(def));
            Constants.LOG.info("[numen-mcp] wrote default config {} and minted an access token for agent "
                    + "'default' -- open the file to copy it into a connecting client's config. Keep it "
                    + "private: it grants remote control of your companions.", file);
            return def;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (!o.has("agents")) {
                // Pre-W10 schema: one shared `token` string, blank meaning "no auth" even on a LAN
                // bind. Migrate it into a single "default" agent verbatim (never mint a replacement
                // here — that would silently change a working local setup) and back the old file up
                // before touching it, so a hand-edited config is never lost to a schema change.
                McpConfig migrated = new McpConfig(
                        o.has("enabled") ? o.get("enabled").getAsBoolean() : true,
                        strOr(o, "host", DEFAULT_HOST),
                        o.has("port") ? o.get("port").getAsInt() : DEFAULT_PORT,
                        List.of(new Agent("default", "Default", strOr(o, "token", ""))),
                        DEFAULT_SESSION_TTL_SECONDS,
                        o.has("call_timeout_seconds") ? o.get("call_timeout_seconds").getAsInt() : DEFAULT_CALL_TIMEOUT_SECONDS,
                        o.has("hidden_tools") ? strings(o, "hidden_tools") : DEFAULT_HIDDEN,
                        false, false);
                if (backup(file)) {
                    writeJson(file, toJson(migrated));
                    Constants.LOG.info("[numen-mcp] migrated legacy single-token config {} to the per-agent "
                            + "format (previous file saved as {}.bak). If this server is ever bound beyond "
                            + "127.0.0.1/localhost, every agent needs a non-blank token or it will refuse to "
                            + "start.", file, file);
                } else {
                    Constants.LOG.warn("[numen-mcp] could not back up {} before migrating it -- using the "
                            + "migrated config for this session without rewriting the file", file);
                }
                return migrated;
            }
            return new McpConfig(
                    o.has("enabled") ? o.get("enabled").getAsBoolean() : true,
                    strOr(o, "host", DEFAULT_HOST),
                    o.has("port") ? o.get("port").getAsInt() : DEFAULT_PORT,
                    parseAgents(o),
                    o.has("session_ttl_seconds") ? o.get("session_ttl_seconds").getAsInt() : DEFAULT_SESSION_TTL_SECONDS,
                    o.has("call_timeout_seconds") ? o.get("call_timeout_seconds").getAsInt() : DEFAULT_CALL_TIMEOUT_SECONDS,
                    o.has("hidden_tools") ? strings(o, "hidden_tools") : DEFAULT_HIDDEN,
                    o.has("expose_conversation") && o.get("expose_conversation").getAsBoolean(),
                    o.has("expose_usage") && o.get("expose_usage").getAsBoolean());
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.warn("[numen-mcp] unreadable config {} — server disabled: {}", file, ex.toString());
            return new McpConfig(false, DEFAULT_HOST, DEFAULT_PORT, List.of(),
                    DEFAULT_SESSION_TTL_SECONDS, DEFAULT_CALL_TIMEOUT_SECONDS, DEFAULT_HIDDEN, false, false);
        }
    }

    private static List<Agent> parseAgents(JsonObject o) {
        List<Agent> out = new ArrayList<>();
        if (o.has("agents") && o.get("agents").isJsonArray()) {
            int i = 0;
            for (JsonElement el : o.getAsJsonArray("agents")) {
                if (!el.isJsonObject()) continue;
                JsonObject a = el.getAsJsonObject();
                String id = strOr(a, "id", "agent-" + i);
                out.add(new Agent(id, strOr(a, "label", id), strOr(a, "token", "")));
                i++;
            }
        }
        return List.copyOf(out);
    }

    /** Copies {@code file} to {@code <file>.bak}, overwriting any previous backup. Returns success. */
    private static boolean backup(Path file) {
        Path bak = file.resolveSibling(file.getFileName().toString() + ".bak");
        try {
            Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-mcp] failed to back up {} to {}: {}", file, bak, ex.toString());
            return false;
        }
    }

    private static JsonObject toJson(McpConfig cfg) {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", cfg.enabled());
        o.addProperty("host", cfg.host());
        o.addProperty("port", cfg.port());
        JsonArray agents = new JsonArray();
        for (Agent a : cfg.agents()) {
            JsonObject ao = new JsonObject();
            ao.addProperty("id", a.id());
            ao.addProperty("label", a.label());
            ao.addProperty("token", a.token());
            agents.add(ao);
        }
        o.add("agents", agents);
        o.addProperty("session_ttl_seconds", cfg.sessionTtlSeconds());
        o.addProperty("call_timeout_seconds", cfg.callTimeoutSeconds());
        JsonArray hidden = new JsonArray();
        cfg.hiddenTools().forEach(hidden::add);
        o.add("hidden_tools", hidden);
        o.addProperty("expose_conversation", cfg.exposeConversation());
        o.addProperty("expose_usage", cfg.exposeUsage());
        return o;
    }

    private static void writeJson(Path file, JsonObject o) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, o.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-mcp] failed to write config {}: {}", file, ex.toString());
        }
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray(key)) out.add(el.getAsString());
        }
        return List.copyOf(out);
    }

    private static String strOr(JsonObject o, String key, String fallback) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? fallback : el.getAsString();
    }
}
