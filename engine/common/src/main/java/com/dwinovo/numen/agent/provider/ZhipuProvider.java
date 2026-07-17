package com.dwinovo.numen.agent.provider;

import com.google.gson.JsonObject;

/**
 * Zhipu AI (智谱 / GLM) provider — OpenAI-compatible, so just the endpoint differs. Its chat-completions
 * API lives under {@code .../api/paas/v4}; the GLM-4.x family (glm-4.6, glm-4.5-air, glm-4-flash, …) speaks
 * the standard OpenAI wire format, including tools. Reasoning fields round-trip via the base extras-capture.
 */
public final class ZhipuProvider extends OpenAIProvider {

    public static final String NAME = "zhipu";
    public static final String DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4";

    @Override public String name() { return NAME; }

    @Override public String defaultBaseUrl() { return DEFAULT_BASE_URL; }

    /**
     * Zhipu reasoning knobs, applied per the configured effort:
     * <ul>
     *   <li>{@code auto} (or null/blank) → send nothing. NOTE: zhipu's server default for thinking
     *       models is thinking ENABLED at max effort — "auto" is the most expensive option, not the
     *       cheapest.</li>
     *   <li>{@code off} → {@code thinking:{"type":"disabled"}}; no {@code reasoning_effort}, no
     *       {@code clear_thinking}.</li>
     *   <li>{@code minimal|low|medium|high} → {@code thinking:{"type":"enabled"}} plus
     *       {@code clear_thinking:false} (zhipu defaults it to TRUE, making the server discard all
     *       historical thinking before the model sees it — false preserves the model's own past
     *       reasoning chain in context, so plan state carries across turns within a task) plus
     *       {@code reasoning_effort} (GLM-5.2 accepts max|xhigh|high|medium|low|minimal|none).</li>
     * </ul>
     * Model gates: the {@code thinking} on/off toggle exists since the GLM-4.5 family, so it's sent
     * only for the {@code glm-4.5}, {@code glm-4.6} and {@code glm-5} prefixes (older glm-4.x rejects
     * unknown params). {@code reasoning_effort} is GLM-5.2-only — sending it to glm-4.x yields
     * {@code invalid_parameter_error} — so it's gated separately on the {@code glm-5.2} prefix.
     *
     * <p>Context-growth note: with {@code clear_thinking:false} the conversation grows append-only
     * with reasoning content (the prompt-cache prefix stays stable, so caching still hits); the
     * engine's auto-compaction eventually folds it into summaries as usual.
     */
    @Override
    public void applyReasoning(JsonObject body, String model, String effort) {
        if (effort == null || effort.isBlank() || effort.equalsIgnoreCase("auto")) return;
        if (!supportsThinkingToggle(model)) return;
        JsonObject thinking = new JsonObject();
        if (effort.equalsIgnoreCase("off")) {
            thinking.addProperty("type", "disabled");
            body.add("thinking", thinking);
            return;
        }
        thinking.addProperty("type", "enabled");
        body.add("thinking", thinking);
        body.addProperty("clear_thinking", false);
        if (model.startsWith("glm-5.2")) {
            body.addProperty("reasoning_effort", effort);
        }
    }

    /** The {@code thinking} request field exists since GLM-4.5 — don't send it to older glm-4.x. */
    private static boolean supportsThinkingToggle(String model) {
        return model != null && (model.startsWith("glm-4.5")
                || model.startsWith("glm-4.6")
                || model.startsWith("glm-5"));
    }
}
