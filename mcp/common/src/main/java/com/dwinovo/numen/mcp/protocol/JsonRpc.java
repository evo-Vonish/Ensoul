package com.dwinovo.numen.mcp.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * JSON-RPC 2.0 envelope construction, extracted from the old monolithic server so the transport
 * and the tool surface can both speak it without either owning it.
 *
 * <p>Error codes are the standard set plus MCP's convention of using {@code -32602} for a
 * well-formed request whose arguments are wrong. Note the deliberate distinction this class
 * exists to keep visible: a <em>protocol</em> error (this class) means the request itself was
 * malformed or unroutable; a <em>tool</em> error is a successful JSON-RPC response whose result
 * carries {@code isError: true}. Conflating them is how an agent ends up retrying a call that
 * will never work, or giving up on one that merely reported a failed action.
 */
public final class JsonRpc {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    private JsonRpc() {}

    public static JsonObject ok(JsonElement id, JsonObject result) {
        JsonObject r = new JsonObject();
        r.addProperty("jsonrpc", "2.0");
        r.add("id", id == null ? JsonNull.INSTANCE : id);
        r.add("result", result);
        return r;
    }

    public static JsonObject error(JsonElement id, int code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject r = new JsonObject();
        r.addProperty("jsonrpc", "2.0");
        r.add("id", id == null ? JsonNull.INSTANCE : id);
        r.add("error", err);
        return r;
    }

    /** A server-to-client notification: no id, no reply expected. Carried over SSE. */
    public static JsonObject notification(String method, JsonObject params) {
        JsonObject n = new JsonObject();
        n.addProperty("jsonrpc", "2.0");
        n.addProperty("method", method);
        n.add("params", params == null ? new JsonObject() : params);
        return n;
    }

    /** The MCP tool-result envelope: text content plus the tool-level error flag. */
    public static JsonObject content(String text, boolean isError) {
        JsonObject item = new JsonObject();
        item.addProperty("type", "text");
        item.addProperty("text", text == null ? "" : text);
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        arr.add(item);
        JsonObject result = new JsonObject();
        result.add("content", arr);
        result.addProperty("isError", isError);
        return result;
    }

    /** {@code params.arguments}, or an empty object — never null, never a class-cast surprise. */
    public static JsonObject argsOf(JsonObject request) {
        JsonObject params = obj(request, "params");
        return obj(params, "arguments");
    }

    public static JsonObject obj(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) return new JsonObject();
        return parent.getAsJsonObject(key);
    }

    public static String str(JsonObject parent, String key, String fallback) {
        if (parent == null || !parent.has(key) || parent.get(key).isJsonNull()) return fallback;
        try {
            return parent.get(key).getAsString();
        } catch (RuntimeException notAString) {
            return fallback;
        }
    }
}
