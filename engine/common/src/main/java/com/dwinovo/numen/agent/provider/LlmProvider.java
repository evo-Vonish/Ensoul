package com.dwinovo.numen.agent.provider;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.List;

/**
 * Wire-format adapter for one LLM backend family.
 *
 * <h2>Why this interface exists</h2>
 * Most "OpenAI-compatible" backends are 95% compatible and 5% proprietary
 * extensions — DeepSeek adds {@code reasoning_content}, Azure renames
 * a few fields, Anthropic via OpenAI-compat proxy doesn't quite return the
 * same shape. Centralising the wire-format translation here keeps the rest
 * of the agent layer (ConvoState, AgentLoop, ToolAdapter) provider-agnostic
 * — they speak our internal types ({@link AssistantTurn}, {@link LlmToolCall})
 * and the provider does the JSON in/out.
 *
 * <p>For genuinely non-OpenAI protocols (Anthropic Messages API, Gemini)
 * you'd implement this interface from scratch with a different request /
 * response shape. The interface deliberately avoids assuming OpenAI fields
 * leak through to the caller.
 *
 * <h2>What providers DO</h2>
 * Build wire messages, build request bodies, parse response bodies, and
 * handle the round-trip of non-standard fields ({@link AssistantTurn#extras}).
 *
 * <h2>What providers DON'T do</h2>
 * No HTTP I/O (that's {@link com.dwinovo.numen.agent.http.HttpLlmTransport}),
 * no convo state (that's {@code ConvoState}), no LLM-side loop control
 * (that's {@code EntityAgentLoop}).
 */
public interface LlmProvider {

    /** Stable id used in config (e.g. {@code "openai"}, {@code "deepseek"}). */
    String name();

    /**
     * Default API base URL for this provider, used when the user leaves
     * {@code config.baseUrl} empty. Matches LiteLLM's
     * {@code _get_openai_compatible_provider_info} return value: includes the
     * version / mode prefix ({@code /v1}, {@code /beta},
     * {@code /compatible-mode/v1}, {@code /api/v3}, ...) but **excludes**
     * the trailing {@code /chat/completions} path — that's appended by
     * {@code NumenLlmClient} during URL composition.
     *
     * <p>Examples per provider:
     * <ul>
     *   <li>OpenAI → {@code https://api.openai.com/v1}</li>
     *   <li>DeepSeek → {@code https://api.deepseek.com/beta}</li>
     *   <li>Moonshot (Kimi) → {@code https://api.moonshot.ai/v1}</li>
     *   <li>MiniMax → {@code https://api.minimax.io/v1}</li>
     *   <li>Volcengine (Doubao) → {@code https://ark.cn-beijing.volces.com/api/v3}</li>
     *   <li>DashScope (Qwen) → {@code https://dashscope.aliyuncs.com/compatible-mode/v1}</li>
     * </ul>
     */
    String defaultBaseUrl();

    /** Build the user-role wire message for {@code content}. */
    JsonObject buildUserMessage(String content);

    /**
     * Build the user-role wire message for {@code content} that may carry image
     * {@code attachmentPaths} (absolute paths to persisted image files).
     *
     * <p>When there are no attachments the result MUST be byte-identical to
     * {@link #buildUserMessage(String)} — a plain {@code content} string — so the
     * common text-only case keeps its prompt-cache-stable shape.
     *
     * <p>{@code includeImages} gates whether the image bytes are actually inlined
     * (as OpenAI multimodal {@code image_url} data URLs). The caller passes
     * {@code true} only for the MOST RECENT user message and {@code false} for
     * older ones, so history doesn't re-ship every image on every turn — older
     * messages degrade to text plus a short "[image omitted]" placeholder.
     *
     * <p>Default: ignore attachments entirely (text-only backends). The
     * OpenAI-compatible family overrides this in {@link OpenAIProvider}; every
     * OpenAI-compat subclass (Zhipu, DeepSeek, …) inherits that override.
     */
    default JsonObject buildUserMessage(String content, java.util.List<String> attachmentPaths,
                                        boolean includeImages) {
        return buildUserMessage(content);
    }

    /** Build the system-role wire message for {@code content}. */
    JsonObject buildSystemMessage(String content);

    /** Build the tool-role wire message echoing {@code toolCallId}'s result. */
    JsonObject buildToolResultMessage(String toolCallId, String content);

    /**
     * Convert a stored {@link AssistantTurn} back into the wire-format
     * assistant message for the next request. Must re-inject {@code extras}
     * so backends with proprietary required-echo fields stay happy.
     */
    JsonObject assistantToRequestMessage(AssistantTurn turn);

    /** Build the {@code tools} array from our internal {@link NumenTool} registry. */
    JsonArray buildToolList(Collection<NumenTool> tools);

    /**
     * Assemble the full request body for one chat completion.
     *
     * @param model         model id string ({@code "deepseek-chat"}, {@code "gpt-4o"}, ...)
     * @param systemPrompt  may be empty — provider should skip if so
     * @param messages      already-built wire-format message objects in order
     * @param tools         already-built wire-format tools array (may be empty)
     */
    JsonObject buildRequestBody(String model, String systemPrompt,
                                List<JsonObject> messages, JsonArray tools);

    /**
     * Apply the user-configured reasoning-effort knob to an already-built
     * request {@code body}. No-op by default: most backends don't expose a
     * reasoning_effort parameter, and passing one where it isn't supported is
     * an error. Providers that support it override this, gating on both the
     * {@code effort} value ({@code null}/blank/{@code "auto"} means "leave the
     * body alone") and the {@code model} (the parameter is often model-family
     * specific).
     *
     * @param body    the mutable request body to augment in place
     * @param model   the model id the request targets
     * @param effort  configured effort ({@code auto}/{@code off}/{@code minimal}/
     *                {@code low}/{@code medium}/{@code high}); {@code auto} =
     *                don't send anything, {@code off} = disable thinking where
     *                the provider supports a toggle
     */
    default void applyReasoning(JsonObject body, String model, String effort) {}

    /** Decode the response body into our internal {@link AssistantTurn}. */
    AssistantTurn parseResponseBody(JsonObject body);

    // ---- account balance (optional capability) ----

    /**
     * Absolute URL of this backend's key-scoped account-balance endpoint, or {@code null}
     * when the provider has no such API (OpenAI: dashboard-only; zhipu: console-only —
     * no documented balance endpoint for standard API keys). {@code configuredBaseUrl} is
     * advisory (region/host selection); most providers return a canonical absolute URL.
     */
    default String balanceUrl(String configuredBaseUrl) { return null; }

    /**
     * Turn a successful balance-endpoint response into a one-line display string, e.g.
     * {@code "余额: ¥12.34 (赠送 ¥0.50)"}. Only called when {@link #balanceUrl} returned
     * non-null and the GET succeeded; implementations should be tolerant of missing fields.
     */
    default String parseBalance(JsonObject body) { return null; }

    // ---- streaming ----

    /**
     * Whether this provider supports SSE streaming. Default true for the
     * OpenAI-compat family; backends that only support buffered responses
     * (rare) can override to false.
     */
    default boolean supportsStreaming() { return true; }

    /**
     * Apply one SSE chunk's JSON to the running {@link StreamAccumulator}.
     * Called from the HTTP layer's chunk handler in order. Implementations
     * must be tolerant of missing fields — backends send sparse deltas.
     */
    void accumulateChunk(JsonObject chunk, StreamAccumulator acc);

    /**
     * Convert a fully-accumulated streaming response into our internal
     * {@link AssistantTurn}. Called once after the stream terminates
     * normally.
     */
    AssistantTurn finalizeStream(StreamAccumulator acc);
}
