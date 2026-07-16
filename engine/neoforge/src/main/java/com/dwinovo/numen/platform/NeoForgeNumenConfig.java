package com.dwinovo.numen.platform;

import com.dwinovo.numen.platform.services.INumenConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * NeoForge implementation of {@link INumenConfig}. Backed by a
 * {@code ModConfigSpec} that NeoForge serialises as TOML under
 * {@code <gameDir>/config/numen-common.toml}. Editable in-game via
 * NeoForge's built-in config screen (Mods → Numen → Config).
 *
 * <h2>Registration timing</h2>
 * The static {@link #SPEC} is constructed during class load (just data —
 * no I/O), so it's safe to reference from the Mod constructor. The Mod
 * registers the spec via {@code ModContainer.registerConfig(...)}; NeoForge
 * loads / writes the TOML when the world loads.
 *
 * <p>Calling {@link ModConfigSpec.ConfigValue#get()} before the spec has
 * been loaded returns the declared default, so reads from this class are
 * safe at any point after {@code SPEC} is registered.
 */
public final class NeoForgeNumenConfig implements INumenConfig {

    public static final ModConfigSpec.ConfigValue<String> API_KEY;
    public static final ModConfigSpec.ConfigValue<String> BASE_URL;
    public static final ModConfigSpec.ConfigValue<String> MODEL;
    public static final ModConfigSpec.ConfigValue<String> PROVIDER;
    public static final ModConfigSpec.ConfigValue<String> PROXY;
    public static final ModConfigSpec.ConfigValue<String> SYSTEM_PROMPT;
    public static final ModConfigSpec.ConfigValue<String> REASONING_EFFORT;
    public static final ModConfigSpec.ConfigValue<String> EMERGENCY_EFFORT;
    public static final ModConfigSpec.BooleanValue ASYNC_COMPACTION;
    public static final ModConfigSpec SPEC;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("OpenAI / OpenAI-compatible API settings.").push("openai");

        API_KEY = b.comment("API key (sk-...) sent as the bearer token. Required.")
                .define("api_key", "");
        BASE_URL = b.comment("Base URL override. Leave empty for OpenAI's default endpoint.",
                "Examples: https://api.deepseek.com, http://localhost:11434 (Ollama)")
                .define("base_url", "");
        MODEL = b.comment("Model id sent as the `model` field. Backend-defined.")
                .define("model", "gpt-5-2-mini");
        PROVIDER = b.comment("Wire-format adapter AND default base URL selector.",
                "openai      → api.openai.com/v1 (default; also generic OpenAI-compat fallback)",
                "deepseek    → api.deepseek.com/beta (preserves reasoning_content for V4 thinking)",
                "moonshot    → api.moonshot.ai/v1 (Kimi; fill_reasoning_content safety net)",
                "  (alias: kimi)",
                "minimax     → api.minimax.io/v1",
                "volcengine  → ark.cn-beijing.volces.com/api/v3 (Doubao)",
                "  (aliases: doubao, ark)",
                "dashscope   → dashscope.aliyuncs.com/compatible-mode/v1 (Alibaba Qwen / Tongyi)",
                "  (aliases: qwen, tongyi, aliyun)",
                "Override 'base_url' below to use a different host (proxy / region split).")
                .define("provider", "openai");
        PROXY = b.comment("Optional HTTP proxy for LLM calls as host:port (empty = direct).")
                .define("proxy", "");

        b.pop();
        b.comment("Behaviour tuning.").push("agent");
        // Deliberately short. The planning behaviour (use todowrite for
        // multi-step tasks, load_skill to fetch detailed workflows) emerges
        // entirely from those tools' own descriptions plus the runtime-injected
        // <available_skills> XML block — adding rules here just dilutes
        // attention. Mirrors opencode's default.txt minimalist style.
        SYSTEM_PROMPT = b.comment("System prompt prepended to every conversation.")
                .define("system_prompt",
                        "You are Numen, a Minecraft entity controlled by the player who owns you.\n"
                                + "Use the tools provided to act in the world; output text only to talk to your owner.");
        REASONING_EFFORT = b.comment(
                "Reasoning effort for reasoning-capable models (currently GLM via the zhipu provider).",
                "auto (default) → send nothing; the backend decides (zhipu's default is thinking ON at max effort!).",
                "off → disable thinking (glm-4.5+). Effort values: minimal | low | medium | high (GLM-5.2 only).",
                "Ignored by providers / models that don't support it.")
                .define("reasoning_effort", "auto");
        EMERGENCY_EFFORT = b.comment(
                "Reasoning effort for EMERGENCY turns — the agent reacting to an urgent world event",
                "(an incoming attack, a hazard) that preempts a slow think already in flight.",
                "off (default) → react immediately with no thinking. Same value space as reasoning_effort.",
                "Ignored by providers / models that don't support it.")
                .define("emergency_effort", "off");
        ASYNC_COMPACTION = b.comment(
                "ICE Phase 1 — asynchronous context compaction.",
                "true (default) → when the context crosses a soft threshold (0.7x the model window),",
                "  summarize the oldest ~90% in the BACKGROUND and splice it in at a turn boundary,",
                "  so the companion never freezes for the compaction round-trip.",
                "false → only the legacy blocking compaction at the hard limit (window - 13k) runs.")
                .define("async_compaction", true);
        b.pop();

        SPEC = b.build();
    }

    /** Default constructor used by {@code ServiceLoader}. */
    public NeoForgeNumenConfig() {}

    @Override
    public String getApiKey() {
        return safe(API_KEY);
    }

    @Override
    public String getBaseUrl() {
        return safe(BASE_URL);
    }

    @Override
    public String getModel() {
        return safe(MODEL);
    }

    @Override
    public String getSystemPrompt() {
        return safe(SYSTEM_PROMPT);
    }

    @Override
    public String getProvider() {
        String s = safe(PROVIDER);
        return s.isEmpty() ? "openai" : s;
    }

    @Override
    public String getProxy() {
        return safe(PROXY);
    }

    @Override
    public String getReasoningEffort() {
        String s = safe(REASONING_EFFORT);
        return s.isEmpty() ? "auto" : s;
    }

    @Override
    public String getEmergencyEffort() {
        String s = safe(EMERGENCY_EFFORT);
        return s.isEmpty() ? "off" : s;
    }

    @Override
    public boolean isAsyncCompaction() {
        return safeBool(ASYNC_COMPACTION, true);
    }

    // ---- mutations ----

    @Override
    public void setApiKey(String value) {
        API_KEY.set(value == null ? "" : value);
    }

    @Override
    public void setBaseUrl(String value) {
        BASE_URL.set(value == null ? "" : value);
    }

    @Override
    public void setModel(String value) {
        MODEL.set(value == null ? "" : value);
    }

    @Override
    public void setProvider(String value) {
        PROVIDER.set(value == null ? "openai" : value);
    }

    @Override
    public void setProxy(String value) {
        PROXY.set(value == null ? "" : value);
    }

    @Override
    public void setSystemPrompt(String value) {
        SYSTEM_PROMPT.set(value == null ? "" : value);
    }

    @Override
    public void setReasoningEffort(String value) {
        REASONING_EFFORT.set(value == null || value.isBlank() ? "auto" : value);
    }

    @Override
    public void setEmergencyEffort(String value) {
        EMERGENCY_EFFORT.set(value == null || value.isBlank() ? "off" : value);
    }

    @Override
    public void setAsyncCompaction(boolean value) {
        ASYNC_COMPACTION.set(value);
    }

    /**
     * Flush the in-memory config to disk. The setters above only mutate the
     * loaded NightConfig in memory — {@link ModConfigSpec.ConfigValue#set}'s
     * own javadoc states it "does NOT write to disk... call
     * {@link ModConfigSpec#save()} eventually". Without this explicit save an
     * in-game settings change is lost on shutdown (the values revert to
     * whatever was last on disk), which is exactly the "reverts to default"
     * bug players hit after changing provider / key in the GUI.
     *
     * <p>Guarded by {@link ModConfigSpec#isLoaded()}: {@code save()} throws if
     * the spec hasn't been bound to a config file yet (e.g. called absurdly
     * early). In that case the mutations live in the cached values and the
     * normal NeoForge load/correct cycle will persist them.
     */
    @Override
    public void save() {
        if (SPEC.isLoaded()) {
            SPEC.save();
        }
        com.dwinovo.numen.Constants.LOG.info(
                "[numen-config] saved (api_key length={}, provider={}, model={})",
                safe(API_KEY).length(), safe(PROVIDER), safe(MODEL));
    }

    /**
     * Guard against the (rare) corner case where a config value is read
     * before the spec is fully bound — returns the empty string instead of
     * letting an exception escape into the LLM call site.
     */
    private static String safe(ModConfigSpec.ConfigValue<String> v) {
        try {
            String s = v.get();
            return s == null ? "" : s;
        } catch (IllegalStateException ex) {
            return "";
        }
    }

    /** {@link #safe} for a boolean value — returns {@code dflt} if read before the spec is bound. */
    private static boolean safeBool(ModConfigSpec.ConfigValue<Boolean> v, boolean dflt) {
        try {
            Boolean b = v.get();
            return b == null ? dflt : b;
        } catch (IllegalStateException ex) {
            return dflt;
        }
    }
}
