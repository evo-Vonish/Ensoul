package com.dwinovo.numen.platform.services;

/**
 * Cross-loader read surface for user-editable mod configuration. Each loader
 * ships its own implementation hooked into its native config system —
 * NeoForge wraps a {@code ModConfigSpec} (TOML), Fabric reads a Gson JSON
 * file under {@code <gameDir>/config/numen.json}.
 *
 * <h2>Read freshness</h2>
 * Values are read on every call. NeoForge's {@code ConfigValue.get()} is
 * already lazy + cached at the framework level; Fabric's JSON impl caches
 * the parsed values in memory and only re-reads on explicit
 * {@code /reload}-style triggers (Phase-2). For MVP, edits to the config
 * file require a server restart to take effect — acceptable.
 *
 * <h2>Why no setters</h2>
 * Mutation paths differ wildly between loaders (NeoForge needs the screen
 * config GUI, Fabric needs an external editor or our own command). Keep
 * this interface read-only; a future {@code INumenConfigEditor} can layer
 * on top when we need it.
 */
public interface INumenConfig {

    /**
     * OpenAI / OpenAI-compatible API key. Empty string if unset — the LLM
     * client refuses to start a chat completion in that case and logs once.
     */
    String getApiKey();

    /**
     * Optional base URL override. Empty string means "use the SDK default"
     * ({@code https://api.openai.com/v1}). Set to e.g.
     * {@code https://api.deepseek.com/v1} to point at a compatible backend.
     */
    String getBaseUrl();

    /**
     * Model id passed to the {@code model} field of every chat completion
     * request. Backend-defined string ({@code gpt-5-2-mini},
     * {@code claude-3-5-sonnet} via proxy, {@code deepseek-chat}, etc.).
     */
    String getModel();

    /**
     * Provider id selecting the wire-format adapter. Recognised values:
     * <ul>
     *   <li>{@code "openai"} (default) — vanilla OpenAI chat completions shape.
     *       Works with most "OpenAI-compatible" backends (Together, Groq,
     *       Mistral La Plateforme, Moonshot Kimi, Ollama, vLLM, ...).</li>
     *   <li>{@code "deepseek"} — same wire shape but preserves
     *       {@code reasoning_content} on round-trip so thinking models
     *       (deepseek-v3+, deepseek-reasoner) don't 400 on multi-turn.</li>
     * </ul>
     * Unknown values fall back to {@code "openai"}.
     */
    String getProvider();

    /** Optional HTTP proxy for LLM calls as {@code host:port} (empty = direct). For users behind a firewall. */
    String getProxy();

    /**
     * System prompt prepended to every conversation. Empty string for none.
     * The agent layer adds tool-use guidance automatically on top of this.
     */
    String getSystemPrompt();

    /**
     * Reasoning-effort knob for reasoning-capable models. {@code "auto"}
     * (default) means "send nothing — let the backend decide" (careful:
     * zhipu's server default is thinking enabled at MAX effort, so auto is
     * the most expensive option there, not the cheapest). {@code "off"}
     * disables thinking where the provider supports a toggle. The effort
     * values ({@code minimal} / {@code low} / {@code medium} / {@code high})
     * enable thinking and are forwarded to providers that support a
     * {@code reasoning_effort} request field (currently GLM-5.2 via
     * {@code zhipu}). Ignored by providers / models that don't support it.
     */
    String getReasoningEffort();

    /**
     * Reasoning effort used for <em>emergency</em> turns — a turn the agent loop
     * starts in reaction to an urgent injected world event (an incoming attack,
     * a hazard) that must be answered fast, preempting any slow think already in
     * flight. Defaults to {@code "off"} (disable thinking so the reaction lands
     * immediately). Same value space as {@link #getReasoningEffort()}
     * ({@code auto} / {@code off} / {@code minimal} / {@code low} / {@code medium}
     * / {@code high}); ignored by providers / models without a reasoning knob.
     *
     * <p>Config-file only for now — there is no settings-GUI control (follow-up).
     */
    String getEmergencyEffort();

    /**
     * ICE Phase 1 — <em>asynchronous context compaction</em>. When {@code true}
     * (default), the agent loop dispatches a summarization of the oldest ~90% of the
     * context (the ICE "long-track" recast) in the <strong>background</strong> as soon
     * as the live context crosses a soft water line (0.7 × the model window), and splices
     * the summary in at the next turn boundary — so the companion never stalls for a
     * compaction round-trip. When {@code false}, only the legacy blocking auto-compaction
     * at the hard limit (window − 13k) runs.
     *
     * <p>Config-file only for now — there is no settings-GUI control (follow-up), same as
     * {@link #getEmergencyEffort()}.
     */
    boolean isAsyncCompaction();

    // ---- write surface (client-side: invoked by SettingsScreen) ----

    /**
     * Mutate the in-memory API key. Caller must {@link #save()} to persist.
     * Null values are normalised to empty string.
     */
    void setApiKey(String value);

    void setBaseUrl(String value);

    void setModel(String value);

    void setProvider(String value);

    void setProxy(String value);

    void setSystemPrompt(String value);

    /** Mutate the in-memory reasoning-effort knob. Null/blank normalised to {@code "auto"}. */
    void setReasoningEffort(String value);

    /** Mutate the in-memory emergency-effort knob. Null/blank normalised to {@code "off"}. */
    void setEmergencyEffort(String value);

    /** Toggle {@link #isAsyncCompaction()}. Config-file only (no settings GUI). */
    void setAsyncCompaction(boolean value);

    /**
     * Flush in-memory changes to the loader-native config file. Best-effort:
     * errors are logged but never thrown — settings GUI shouldn't crash the
     * client because of a write failure.
     *
     * <p>Fabric impl rewrites {@code config/numen.json} directly. NeoForge
     * impl writes through {@code ModConfigSpec.ConfigValue.set()}, which
     * NeoForge persists to {@code numen-common.toml} on its own schedule
     * (this method nudges that schedule when possible).
     */
    void save();
}
