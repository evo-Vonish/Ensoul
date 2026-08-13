package com.dwinovo.numen.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 *   <li>{@code token} — optional bearer token; when set, requests must present it
 *       (Authorization header or {@code ?token=}). Empty = no auth (fine on
 *       loopback);</li>
 *   <li>{@code call_timeout_seconds} — how long one {@code tools/call} waits for a
 *       body action to finish before reporting a timeout;</li>
 *   <li>{@code hidden_tools} — engine tools NOT exposed to the external agent
 *       (agent-internal bookkeeping the external brain has no business calling);</li>
 *   <li>{@code coordinator_enabled} — cluster coordinator (broadcast / whisper / trade)
 *       master switch, default true;</li>
 *   <li>{@code trade_distance} — max blocks between parties to invite/accept a trade,
 *       default 3.0;</li>
 *   <li>{@code invite_timeout_seconds} — lazy expiry for trade invitations, default 120;</li>
 *   <li>{@code mailbox_capacity} — per-companion inbox size; oldest dropped on overflow,
 *       default 50.</li>
 * </ul>
 */
public record McpConfig(
        boolean enabled,
        String host,
        int port,
        String token,
        int callTimeoutSeconds,
        List<String> hiddenTools,
        boolean coordinatorEnabled,
        double tradeDistance,
        int inviteTimeoutSeconds,
        int mailboxCapacity) {

    /** Tools the built-in brain manages for itself — never handed to an external driver. */
    private static final List<String> DEFAULT_HIDDEN = List.of("todowrite", "load_skill");

    public static McpConfig load(Path file) {
        if (!Files.isRegularFile(file)) {
            McpConfig def = new McpConfig(true, "127.0.0.1", 8765, "", 300, DEFAULT_HIDDEN,
                    true, 3.0, 120, 50);
            writeDefault(file, def);
            return def;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return new McpConfig(
                    o.has("enabled") ? o.get("enabled").getAsBoolean() : true,
                    strOr(o, "host", "127.0.0.1"),
                    o.has("port") ? o.get("port").getAsInt() : 8765,
                    strOr(o, "token", ""),
                    o.has("call_timeout_seconds") ? o.get("call_timeout_seconds").getAsInt() : 300,
                    o.has("hidden_tools") ? strings(o, "hidden_tools") : DEFAULT_HIDDEN,
                    o.has("coordinator_enabled") ? o.get("coordinator_enabled").getAsBoolean() : true,
                    o.has("trade_distance") ? o.get("trade_distance").getAsDouble() : 3.0,
                    o.has("invite_timeout_seconds") ? o.get("invite_timeout_seconds").getAsInt() : 120,
                    o.has("mailbox_capacity") ? o.get("mailbox_capacity").getAsInt() : 50);
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.warn("[numen-mcp] unreadable config {} — server disabled: {}", file, ex.toString());
            return new McpConfig(false, "127.0.0.1", 8765, "", 300, DEFAULT_HIDDEN,
                    true, 3.0, 120, 50);
        }
    }

    public boolean isHidden(String toolName) {
        return hiddenTools.contains(toolName);
    }

    private static void writeDefault(Path file, McpConfig def) {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", def.enabled());
        o.addProperty("host", def.host());
        o.addProperty("port", def.port());
        o.addProperty("token", def.token());
        o.addProperty("call_timeout_seconds", def.callTimeoutSeconds());
        JsonArray hidden = new JsonArray();
        def.hiddenTools().forEach(hidden::add);
        o.add("hidden_tools", hidden);
        o.addProperty("coordinator_enabled", def.coordinatorEnabled());
        o.addProperty("trade_distance", def.tradeDistance());
        o.addProperty("invite_timeout_seconds", def.inviteTimeoutSeconds());
        o.addProperty("mailbox_capacity", def.mailboxCapacity());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, o.toString(), StandardCharsets.UTF_8);
            Constants.LOG.info("[numen-mcp] wrote default config {}", file);
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-mcp] failed to write default config {}: {}", file, ex.toString());
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
