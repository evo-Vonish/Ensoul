package com.dwinovo.numen.agent.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * DeepSeek-flavour OpenAI provider. Strictly aligned with LiteLLM's
 * <a href="https://github.com/BerriAI/litellm/blob/main/litellm/llms/deepseek/chat/transformation.py">{@code DeepSeekChatConfig}</a>
 * reference implementation.
 *
 * <h2>What LiteLLM does for DeepSeek (and so do we)</h2>
 * <ol>
 *   <li><b>Endpoint:</b> defaults to {@code /beta/chat/completions} —
 *       LiteLLM's {@code _get_openai_compatible_provider_info} returns
 *       {@code https://api.deepseek.com/beta} as the base URL. The
 *       {@code /beta} prefix unlocks DeepSeek's prefix-completion family
 *       of features alongside standard chat completions.</li>
 *   <li><b>Inherits all message handling:</b> LiteLLM's class extends
 *       {@code OpenAIGPTConfig} and overrides nothing about response
 *       parsing or message reconstruction. Non-standard response fields
 *       (notably {@code reasoning_content} from V4 thinking mode) are
 *       preserved by the framework-level Pydantic mechanism, not by
 *       DeepSeek-specific code. Our equivalent is the
 *       {@link OpenAIProvider#extractExtras} / {@link OpenAIProvider#captureChunkExtras}
 *       default behaviour, which captures every unknown top-level field.</li>
 * </ol>
 *
 * <h2>Where we now go beyond LiteLLM</h2>
 * <ul>
 *   <li><b>{@code reasoning_content} backstop:</b> LiteLLM bets the
 *       framework-level preservation is always enough. It isn't. The field
 *       goes missing whenever the extras capture had nothing to capture —
 *       a stream that carried no {@code reasoning_content} (non-thinking
 *       model, cut-off stream, a turn recovered after an error), or history
 *       replayed from a {@link com.dwinovo.numen.agent.llm.ConvoLog} written
 *       before this field was round-tripped. DeepSeek then 400s the whole
 *       request, and because the offending assistant message is already
 *       <em>persisted</em>, every subsequent launch replays it and 400s
 *       again — the one failure here that a restart cannot clear. We mirror
 *       {@link MoonshotProvider}'s single-space fill in
 *       {@link #assistantToRequestMessage}.</li>
 * </ul>
 *
 * <h2>What LiteLLM does that we don't</h2>
 * <ul>
 *   <li>No content-list to string conversion is needed in our code path
 *       (we always emit content as a plain string from the agent layer).</li>
 * </ul>
 *
 * <h2>Optional thinking-mode parameter</h2>
 * LiteLLM also maps user-supplied {@code thinking} / {@code reasoning_effort}
 * options to DeepSeek's {@code thinking: {type: "enabled"}} request body
 * field. We don't expose a config knob for this yet — V4 models default
 * to thinking mode anyway, so the field is redundant for the common case.
 * Add a config field + {@code buildRequestBody} override when a user needs
 * to force a specific mode.
 */
public final class DeepSeekProvider extends OpenAIProvider {

    public static final String NAME = "deepseek";
    /** LiteLLM default ({@code _get_openai_compatible_provider_info}). */
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com/beta";

    @Override public String name() { return NAME; }

    @Override public String defaultBaseUrl() { return DEFAULT_BASE_URL; }

    /**
     * Thinking-mode backstop, mirroring {@link MoonshotProvider}: DeepSeek rejects an
     * assistant message that carries {@code tool_calls} without {@code reasoning_content}
     * ({@code 400 The reasoning_content in the thinking mode must be passed back to the
     * API}). The parent already echoes captured extras; this fires only when the field
     * is genuinely absent, filling the minimum value the API accepts.
     *
     * <p>Worth the three lines even though LiteLLM omits them: a bad message here is
     * written to the conversation log, so the 400 survives restarts.
     */
    @Override
    public JsonObject assistantToRequestMessage(AssistantTurn turn) {
        JsonObject m = super.assistantToRequestMessage(turn);
        if (m.has("tool_calls") && !m.has("reasoning_content")) {
            m.addProperty("reasoning_content", " ");
        }
        return m;
    }

    /**
     * Documented balance endpoint ({@code GET /user/balance}, Bearer key):
     * <a href="https://api-docs.deepseek.com/api/get-user-balance">api-docs.deepseek.com</a>.
     * Lives on the bare host — NOT under the {@code /beta} chat base.
     */
    @Override
    public String balanceUrl(String configuredBaseUrl) {
        return "https://api.deepseek.com/user/balance";
    }

    /** {@code {"is_available":..,"balance_infos":[{"currency","total_balance","granted_balance",..}]}} */
    @Override
    public String parseBalance(JsonObject body) {
        if (!body.has("balance_infos") || !body.get("balance_infos").isJsonArray()) return null;
        JsonArray infos = body.getAsJsonArray("balance_infos");
        if (infos.isEmpty() || !infos.get(0).isJsonObject()) return null;
        JsonObject info = infos.get(0).getAsJsonObject();
        String sym = "USD".equalsIgnoreCase(str(info, "currency")) ? "$" : "¥";
        String total = str(info, "total_balance");
        String granted = str(info, "granted_balance");
        String s = "余额: " + sym + (total.isEmpty() ? "?" : total);
        if (!granted.isEmpty() && !"0.00".equals(granted)) s += " (赠送 " + sym + granted + ")";
        return s;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }
}
