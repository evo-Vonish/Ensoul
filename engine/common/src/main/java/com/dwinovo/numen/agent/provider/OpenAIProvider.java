package com.dwinovo.numen.agent.provider;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Reference implementation of the OpenAI chat completions wire protocol.
 * Used directly for OpenAI itself and inherited from by lightly-extending
 * subclasses ({@link DeepSeekProvider}) that only need to override
 * response parsing / assistant-message reconstruction.
 *
 * <h2>Schema reference</h2>
 * Targets the {@code POST /v1/chat/completions} shape from OpenAI's API
 * docs as of GPT-4o / GPT-4.1 / GPT-5 family (the same shape DeepSeek,
 * Together, Groq, Mistral La Plateforme, Moonshot, etc. all conform to).
 *
 * <h2>Tool-call argument string vs object</h2>
 * The OpenAI spec ships tool_call.arguments as a JSON-string-typed string
 * (i.e. the JSON object encoded as a string). We preserve that on both
 * read ({@link AssistantTurn#toolCalls} field is also string) and write
 * (re-emit verbatim). Backends differ here and we trust the LLM's own
 * output verbatim.
 */
public class OpenAIProvider implements LlmProvider {

    public static final String NAME = "openai";
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    private static final Gson GSON = new Gson();

    @Override public String name() { return NAME; }
    @Override public String defaultBaseUrl() { return DEFAULT_BASE_URL; }

    @Override
    public JsonObject buildUserMessage(String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "user");
        m.addProperty("content", content == null ? "" : content);
        return m;
    }

    /**
     * Multimodal user message (OpenAI-compatible vision shape). When the message
     * has image attachments AND {@code includeImages} is set, {@code content}
     * becomes the array form:
     * <pre>
     * "content": [
     *   {"type":"text","text":"..."},
     *   {"type":"image_url","image_url":{"url":"data:image/png;base64,..."}}
     * ]
     * </pre>
     * which OpenAI, Zhipu GLM vision models (glm-4.5v / glm-4.6v / glm-5v), and
     * the rest of the OpenAI-compat vision ecosystem all accept. Backends without
     * a vision model simply error on the {@code image_url} part — that error
     * surfaces through the existing chat error path.
     *
     * <p>Text-only messages (no attachments) fall through to the EXACT plain
     * string {@link #buildUserMessage(String)} shape, keeping the prompt-cache
     * prefix stable for the common case.
     *
     * <p>{@code includeImages == false} (an older user message in history) emits
     * text plus a compact "[N image(s) omitted]" note rather than re-shipping the
     * bytes every turn — the cost-control rule the caller enforces by only
     * inlining images for the most recent user message.
     */
    @Override
    public JsonObject buildUserMessage(String content, java.util.List<String> attachmentPaths,
                                       boolean includeImages) {
        if (attachmentPaths == null || attachmentPaths.isEmpty()) {
            return buildUserMessage(content);   // exact text-only shape (cache stability)
        }
        String text = content == null ? "" : content;
        if (!includeImages) {
            // Older turn: keep the text but drop the pixels — a placeholder so the
            // model still knows an image was here without paying to re-send it.
            JsonObject m = new JsonObject();
            m.addProperty("role", "user");
            m.addProperty("content", text + omittedNote(attachmentPaths.size()));
            return m;
        }
        JsonArray parts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", text);
        parts.add(textPart);
        for (String path : attachmentPaths) {
            String dataUrl = toDataUrl(path);
            if (dataUrl == null) continue;   // unreadable file → skip that image
            JsonObject imgPart = new JsonObject();
            imgPart.addProperty("type", "image_url");
            JsonObject imageUrl = new JsonObject();
            imageUrl.addProperty("url", dataUrl);
            imgPart.add("image_url", imageUrl);
            parts.add(imgPart);
        }
        JsonObject m = new JsonObject();
        m.addProperty("role", "user");
        if (parts.size() == 1) {
            // Every image failed to load — degrade to the plain string form.
            m.addProperty("content", text);
        } else {
            m.add("content", parts);
        }
        return m;
    }

    private static String omittedNote(int n) {
        return "\n[" + n + (n == 1 ? " image" : " images") + " omitted from history]";
    }

    /** Read an image file and encode it as a {@code data:} URL, or null if unreadable. */
    private static String toDataUrl(String path) {
        try {
            java.nio.file.Path p = java.nio.file.Path.of(path);
            byte[] bytes = java.nio.file.Files.readAllBytes(p);
            String b64 = java.util.Base64.getEncoder().encodeToString(bytes);
            return "data:" + mimeFor(p.getFileName().toString()) + ";base64," + b64;
        } catch (Exception ex) {
            return null;
        }
    }

    /** MIME type from a file extension; defaults to PNG (the format we persist in). */
    private static String mimeFor(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "image/png";
    }

    @Override
    public JsonObject buildSystemMessage(String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "system");
        m.addProperty("content", content == null ? "" : content);
        return m;
    }

    @Override
    public JsonObject buildToolResultMessage(String toolCallId, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "tool");
        m.addProperty("tool_call_id", toolCallId);
        m.addProperty("content", content == null ? "" : content);
        return m;
    }

    @Override
    public JsonObject assistantToRequestMessage(AssistantTurn turn) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "assistant");
        // Content can be null in OpenAI assistant turns when there are only
        // tool calls. We emit empty string instead — universally accepted.
        m.addProperty("content", turn.content());
        if (turn.hasToolCalls()) {
            JsonArray tcArr = new JsonArray();
            for (LlmToolCall tc : turn.toolCalls()) {
                tcArr.add(tc.toOpenAIJson());
            }
            m.add("tool_calls", tcArr);
        }
        // Re-inject any provider-specific extras (reasoning_content, ...).
        // The extras live at the message level (sibling to role/content)
        // so we copy each top-level entry onto m.
        for (var e : turn.extras().entrySet()) {
            m.add(e.getKey(), e.getValue());
        }
        return m;
    }

    @Override
    public JsonArray buildToolList(Collection<NumenTool> tools) {
        JsonArray arr = new JsonArray();
        for (NumenTool t : tools) {
            JsonObject fn = new JsonObject();
            fn.addProperty("name", t.name());
            fn.addProperty("description", t.description());
            JsonElement params = mapToJson(t.parameterSchema());
            injectCommonParams(params);
            fn.add("parameters", params);
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("type", "function");
            wrapper.add("function", fn);
            arr.add(wrapper);
        }
        return arr;
    }

    /**
     * The two universal optional parameters appended to <em>every</em> tool's schema
     * at this one serialization chokepoint — transparently, so no individual tool
     * (all 40-odd of them, plus any added concurrently) needs editing:
     * <ul>
     *   <li>{@code d} — a short narration the model writes on every call, in the
     *       conversation's language, describing what it is doing right now. The chat
     *       panel shows this human sentence instead of the raw JSON args.</li>
     *   <li>{@code max_seconds} — an optional per-call time-box; when present the
     *       server tightens the task deadline to it (see {@code ToolContext.deadline}).</li>
     * </ul>
     * Both are OPTIONAL (never added to {@code required}); because they are injected
     * into {@code properties}, a schema with {@code additionalProperties:false} stays
     * valid and the model may send them without an "unknown argument" rejection. A
     * tool that already declares either key keeps its own definition. Each tool's own
     * arg parsing simply ignores these two keys — nothing downstream must change.
     */
    static void injectCommonParams(JsonElement params) {
        if (params == null || !params.isJsonObject()) return;
        JsonObject schema = params.getAsJsonObject();
        if (!schema.has("type")) schema.addProperty("type", "object");
        JsonObject props;
        if (schema.has("properties") && schema.get("properties").isJsonObject()) {
            props = schema.getAsJsonObject("properties");
        } else {
            props = new JsonObject();
            schema.add("properties", props);
        }
        if (!props.has("d")) props.add("d", narrationParamSchema());
        if (!props.has("max_seconds")) props.add("max_seconds", maxSecondsParamSchema());
    }

    /** Schema for the universal {@code d} self-narration parameter. */
    static JsonObject narrationParamSchema() {
        JsonObject p = new JsonObject();
        p.addProperty("type", "string");
        p.addProperty("description",
                "用当前对话语言写一句 ≤20 字的短语，描述你此刻用这个工具在做什么（给主人看），"
                + "例如「挖铁矿补装备」「查看背包」「回家存货」。每次调用都要写，"
                + "包括查询/查看类工具也要写。");
        return p;
    }

    /** Schema for the universal optional {@code max_seconds} per-call time-box. */
    static JsonObject maxSecondsParamSchema() {
        JsonObject p = new JsonObject();
        p.addProperty("type", "integer");
        p.addProperty("minimum", 1);
        p.addProperty("description",
                "本次调用允许的最大执行秒数，到时会自动停止并汇报已完成的进度。"
                + "默认由任务自行估算，通常不必填写；只有当你想给这次行动限定时长时才填。");
        return p;
    }

    @Override
    public JsonObject buildRequestBody(String model, String systemPrompt,
                                        List<JsonObject> messages, JsonArray tools) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);

        JsonArray msgs = new JsonArray();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            msgs.add(buildSystemMessage(systemPrompt));
        }
        for (JsonObject m : messages) msgs.add(m);
        body.add("messages", msgs);

        if (tools != null && !tools.isEmpty()) {
            body.add("tools", tools);
            body.addProperty("parallel_tool_calls", true);
        }
        return body;
    }

    @Override
    public AssistantTurn parseResponseBody(JsonObject body) {
        JsonObject msg = extractMessage(body);
        String content = stringOrEmpty(msg.get("content"));
        List<LlmToolCall> toolCalls = parseToolCalls(msg);
        JsonObject extras = extractExtras(msg);
        return new AssistantTurn(content, toolCalls, extras);
    }

    /** Pull {@code choices[0].message} out of the response body. */
    protected JsonObject extractMessage(JsonObject body) {
        if (!body.has("choices")) {
            throw new IllegalArgumentException("response has no 'choices' field: " + body);
        }
        JsonArray choices = body.getAsJsonArray("choices");
        if (choices.isEmpty()) {
            throw new IllegalArgumentException("response 'choices' is empty: " + body);
        }
        JsonElement first = choices.get(0);
        if (!first.isJsonObject() || !first.getAsJsonObject().has("message")) {
            throw new IllegalArgumentException("response choices[0] has no 'message': " + body);
        }
        return first.getAsJsonObject().getAsJsonObject("message");
    }

    /** Pull and decode tool_calls from an assistant message. */
    protected List<LlmToolCall> parseToolCalls(JsonObject msg) {
        if (!msg.has("tool_calls") || !msg.get("tool_calls").isJsonArray()) {
            return List.of();
        }
        JsonArray arr = msg.getAsJsonArray("tool_calls");
        List<LlmToolCall> out = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            if (el.isJsonObject()) {
                out.add(LlmToolCall.fromOpenAIJson(el.getAsJsonObject()));
            }
        }
        return out;
    }

    /**
     * Capture every non-standard top-level field on the assistant message
     * into the extras bag, so it round-trips back on the next request.
     *
     * <p>This is the framework-level behaviour LiteLLM achieves via Pydantic's
     * {@code provider_specific_fields} on its OpenAI parent class — every
     * unknown field on a response gets preserved automatically. With this
     * baseline behaviour, OpenAI-compatible backend variants
     * ({@link DeepSeekProvider} for {@code reasoning_content}, future
     * providers for whatever they invent) don't need to override anything
     * to keep their extensions intact across turns.
     *
     * <p>Pure OpenAI responses contain only the standard fields, so this
     * captures nothing and the extras bag stays empty — no behaviour change
     * for standard OpenAI.
     */
    protected JsonObject extractExtras(JsonObject msg) {
        JsonObject extras = new JsonObject();
        for (var e : msg.entrySet()) {
            if (!STANDARD_MESSAGE_FIELDS.contains(e.getKey())) {
                extras.add(e.getKey(), e.getValue());
            }
        }
        return extras;
    }

    /** Standard fields on an OpenAI assistant response message. */
    private static final java.util.Set<String> STANDARD_MESSAGE_FIELDS = java.util.Set.of(
            "role", "content", "tool_calls", "refusal", "audio", "function_call");

    // ---- streaming ----

    /** Standard fields inside an OpenAI streaming {@code delta} object. */
    private static final java.util.Set<String> STANDARD_DELTA_FIELDS = java.util.Set.of(
            "role", "content", "tool_calls", "refusal");

    @Override
    public void accumulateChunk(JsonObject chunk, StreamAccumulator acc) {
        acc.chunkCount++;

        // Usage typically arrives in the FINAL chunk (when stream_options.include_usage:true).
        if (chunk.has("usage") && chunk.get("usage").isJsonObject()) {
            acc.usage = chunk.getAsJsonObject("usage");
        }

        if (!chunk.has("choices")) return;
        JsonArray choices = chunk.getAsJsonArray("choices");
        if (choices.isEmpty()) return;
        JsonElement first = choices.get(0);
        if (!first.isJsonObject()) return;
        JsonObject choice = first.getAsJsonObject();

        // finish_reason
        if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
            acc.finishReason = choice.get("finish_reason").getAsString();
        }

        // delta — body of the chunk's payload
        if (!choice.has("delta") || !choice.get("delta").isJsonObject()) return;
        JsonObject delta = choice.getAsJsonObject("delta");

        // content fragment
        if (delta.has("content") && delta.get("content").isJsonPrimitive()) {
            acc.content.append(delta.get("content").getAsString());
        }

        // tool_calls fragments (sparse — each delta only carries what changed)
        if (delta.has("tool_calls") && delta.get("tool_calls").isJsonArray()) {
            for (JsonElement el : delta.getAsJsonArray("tool_calls")) {
                if (!el.isJsonObject()) continue;
                accumulateToolCallDelta(el.getAsJsonObject(), acc);
            }
        }

        // Subclass hook for backend-specific non-standard fields (reasoning_content, etc.)
        captureChunkExtras(delta, acc);
    }

    /**
     * Streaming counterpart to {@link #extractExtras}: capture every
     * non-standard string-typed field on the chunk's {@code delta} into
     * the accumulator's extras buffers, so the finalised
     * {@link AssistantTurn} round-trips them on the next request.
     *
     * <p>Aligned with the framework-level non-standard-field preservation
     * we put in {@link #extractExtras}. Same rationale: pure OpenAI
     * streams contain only standard fields, so this is a no-op for OpenAI
     * itself; OpenAI-compat backends get their extensions
     * (e.g. {@code reasoning_content}) preserved automatically.
     */
    protected void captureChunkExtras(JsonObject delta, StreamAccumulator acc) {
        for (var e : delta.entrySet()) {
            if (STANDARD_DELTA_FIELDS.contains(e.getKey())) continue;
            var el = e.getValue();
            if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                String value = el.getAsString();
                // Unchanged: the extras round-trip (DeepSeek/Moonshot depend on it).
                acc.appendExtra(e.getKey(), value);
                // Additionally mirror recognised reasoning deltas into the live,
                // display-only channel. This does NOT touch extras — the reasoning
                // buffer is rendered live and neither persisted nor re-sent.
                if (reasoningDeltaFields().contains(e.getKey())) {
                    acc.reasoning.append(value);
                }
            }
        }
    }

    /**
     * Delta field names that carry the model's live reasoning stream. Mirrored
     * into {@link StreamAccumulator#reasoning} by {@link #captureChunkExtras} for
     * the live "thinking" display. Overridable so backends that name the field
     * differently can extend the set. Purely a display concern — has no bearing
     * on the extras round-trip.
     */
    protected java.util.Set<String> reasoningDeltaFields() {
        return java.util.Set.of("reasoning_content", "reasoning");
    }

    /** Set of delta fields we treat as "standard" — subclasses use this for partition. */
    protected static java.util.Set<String> standardDeltaFields() {
        return STANDARD_DELTA_FIELDS;
    }

    private static void accumulateToolCallDelta(JsonObject tc, StreamAccumulator acc) {
        int index = tc.has("index") && tc.get("index").isJsonPrimitive()
                ? tc.get("index").getAsInt() : 0;
        StreamAccumulator.ToolCallBuilder b = acc.toolCallAt(index);

        if (tc.has("id") && tc.get("id").isJsonPrimitive() && b.id == null) {
            b.id = tc.get("id").getAsString();
        }
        if (tc.has("function") && tc.get("function").isJsonObject()) {
            JsonObject fn = tc.getAsJsonObject("function");
            if (fn.has("name") && fn.get("name").isJsonPrimitive() && b.name == null) {
                b.name = fn.get("name").getAsString();
            }
            if (fn.has("arguments") && fn.get("arguments").isJsonPrimitive()) {
                b.arguments.append(fn.get("arguments").getAsString());
            }
        }
    }

    @Override
    public AssistantTurn finalizeStream(StreamAccumulator acc) {
        List<LlmToolCall> calls = new ArrayList<>(acc.toolCalls.size());
        for (StreamAccumulator.ToolCallBuilder b : acc.toolCalls.values()) {
            if (b.id == null && b.name == null && b.arguments.length() == 0) continue;
            String id = b.id == null ? "" : b.id;
            String name = b.name == null ? "" : b.name;
            calls.add(new LlmToolCall(id, name, b.arguments.toString()));
        }

        JsonObject extras = new JsonObject();
        for (var e : acc.extraBuffers.entrySet()) {
            extras.addProperty(e.getKey(), e.getValue().toString());
        }

        return new AssistantTurn(acc.content.toString(), calls, extras);
    }

    private static String stringOrEmpty(JsonElement el) {
        if (el == null || el.isJsonNull()) return "";
        if (el.isJsonPrimitive()) return el.getAsString();
        return el.toString();
    }

    private static JsonElement mapToJson(Object value) {
        return GSON.toJsonTree(value);
    }
}
