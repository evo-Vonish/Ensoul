package com.dwinovo.numen.mcp.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.api.NumenActuator;
import com.dwinovo.numen.mcp.McpConfig;
import com.dwinovo.numen.mcp.protocol.JsonRpc;
import com.dwinovo.numen.mcp.transport.McpSession;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@code tools/list} and {@code tools/call}, scoped to a {@link McpSession}.
 *
 * <p>This is a faithful port of the surface the old monolithic server exposed, with exactly one
 * semantic change: <b>lease tokens come from the calling session</b> rather than a process-wide
 * map keyed by companion. That single change is what makes multiple simultaneous external agents
 * representable — see {@link McpSession}'s class doc for why the old shape could not express it.
 *
 * <p>The call SEMANTICS are unchanged in this slice and remain synchronous: a world-action tool
 * still holds its JSON-RPC response open until the task finishes. That is the blocking behaviour
 * ICE-MCP §4 replaces with the placeholder protocol, and it is deliberately left alone here so
 * this slice changes one thing at a time. S3 replaces the body of {@link #invokeTool}.
 */
public final class ToolSurface {

    /** Control-plane round trips are fast (one client-thread hop); a long wait means broken. */
    private static final long CONTROL_TIMEOUT_SECONDS = 10;

    /** Lease lifetime requested at acquire. The server clamps this to its own bounds. */
    private static final int DEFAULT_LEASE_TTL_SECONDS = 300;

    private final McpConfig config;
    private final Gson gson = new Gson();

    public ToolSurface(McpConfig config) {
        this.config = config;
    }

    // ============================================================ tools/list

    public JsonObject listResult() {
        JsonArray tools = new JsonArray();

        tools.add(toolDef("list_companions",
                "List the owner's live Minecraft companions (name + id). Call this first to see "
                        + "who you can drive.",
                objectSchema(null, false)));
        tools.add(toolDef("acquire_companion",
                "Take control of a companion so you (not its built-in AI) drive it. Pauses its "
                        + "built-in brain and frees its body. Do this before calling any action "
                        + "tool on it. Refused, naming the holder, if another brain already has it.",
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

    /** An engine tool's own schema with a required {@code companion} argument injected. */
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
        p.addProperty("description",
                "Which companion to act with — its name or id (see list_companions).");
        return p;
    }

    // ============================================================ tools/call

    public JsonObject callResult(JsonObject request, McpSession session) {
        JsonObject params = JsonRpc.obj(request, "params");
        String name = JsonRpc.str(params, "name", "");
        JsonObject args = JsonRpc.obj(params, "arguments");

        try {
            return switch (name) {
                case "list_companions" -> JsonRpc.content(listCompanions(), false);
                case "acquire_companion" -> acquire(args, session);
                case "release_companion" -> release(args, session);
                default -> invokeTool(name, args, session);
            };
        } catch (TimeoutException te) {
            return JsonRpc.content("timed out waiting for the action to finish", true);
        } catch (Exception ex) {
            return JsonRpc.content("call failed: " + ex.getMessage(), true);
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
     * Acquire, attributed to THIS session's agent identity — which is what lets the owner's
     * console distinguish "Claude Code took Alice" from "Codex took Bob" instead of showing two
     * anonymous MCP controllers.
     */
    private JsonObject acquire(JsonObject args, McpSession session) throws Exception {
        UUID target = resolveCompanion(args);
        if (target == null) {
            return JsonRpc.content(
                    "no such companion — call list_companions to see valid names/ids", true);
        }
        NumenActuator.AcquireResult result = NumenActuator
                .acquire(target, session.agentId(), session.label(), DEFAULT_LEASE_TTL_SECONDS)
                .get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!result.ok()) {
            return JsonRpc.content("could not acquire " + target
                    + (result.reason().isEmpty() ? "" : ": " + result.reason()), true);
        }
        session.rememberLease(target, result.sessionToken());
        return JsonRpc.content("acquired " + target
                + " — its built-in brain is paused; you are driving it now", false);
    }

    private JsonObject release(JsonObject args, McpSession session) throws Exception {
        UUID target = resolveCompanion(args);
        if (target == null) {
            return JsonRpc.content(
                    "no such companion — call list_companions to see valid names/ids", true);
        }
        String token = session.tokenFor(target);
        boolean ok = NumenActuator.release(target, token)
                .get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (ok) session.forgetLease(target);
        return JsonRpc.content(ok
                ? "released " + target + " — its built-in brain may act again"
                : "could not release " + target + " (you may not currently hold it)", !ok);
    }

    /**
     * Run an engine tool with this session's lease token attached. The server validates the token
     * against the live lease, so a token belonging to a closed session — or to another agent — is
     * refused there rather than trusted here. The bridge is not, and must not be, the authority.
     */
    private JsonObject invokeTool(String toolName, JsonObject args, McpSession session)
            throws Exception {
        UUID target = resolveCompanion(args);
        if (target == null) {
            return JsonRpc.content(
                    "this tool needs a 'companion' argument (a name or id from list_companions)",
                    true);
        }
        JsonObject toolArgs = args.deepCopy();
        toolArgs.remove("companion");
        String result = NumenActuator
                .invoke(target, toolName, toolArgs.toString(), session.tokenFor(target))
                .get(config.callTimeoutSeconds(), TimeUnit.SECONDS);

        boolean isError = false;
        try {
            JsonObject r = JsonParser.parseString(result).getAsJsonObject();
            isError = r.has("success") && !r.get("success").getAsBoolean();
        } catch (RuntimeException notJson) {
            // A plain-text result is a result, not a failure.
        }
        return JsonRpc.content(result, isError);
    }

    /** Resolve {@code companion} (id, then exact name, then case-insensitive name) or null. */
    private UUID resolveCompanion(JsonObject args) throws Exception {
        if (!args.has("companion") || args.get("companion").isJsonNull()) return null;
        String raw = args.get("companion").getAsString().trim();
        if (raw.isEmpty()) return null;
        List<NumenActuator.Companion> list = NumenActuator.companions()
                .get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        for (NumenActuator.Companion c : list) {
            if (c.uuid().toString().equalsIgnoreCase(raw)) return c.uuid();
        }
        for (NumenActuator.Companion c : list) {
            if (c.name().equals(raw)) return c.uuid();
        }
        for (NumenActuator.Companion c : list) {
            if (c.name().equalsIgnoreCase(raw)) return c.uuid();
        }
        return null;
    }
}
