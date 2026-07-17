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
 * <h2>What LiteLLM does NOT do for DeepSeek (and neither do we now)</h2>
 * <ul>
 *   <li>No {@code fill_reasoning_content} safety net like the Moonshot
 *       provider has. LiteLLM bets the framework-level preservation is
 *       enough; we follow that bet.</li>
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
