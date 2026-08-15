package com.dwinovo.numen.mcp;

import com.dwinovo.numen.api.NumenActuator;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP resources surface (W13) — {@code resources/list}, {@code resources/templates/list}, and
 * {@code resources/read}'s URI resolution + JSON rendering, kept separate from {@link McpServer}
 * so the dispatch/transport file doesn't grow a second responsibility. Every read routes through
 * {@link NumenActuator}'s W13 accessor methods — never a direct {@code client.agent}/{@code
 * entity} import, matching the convention {@link McpServer} already follows (and see {@link
 * NumenActuator}'s own class doc for why {@code UsageStat} exists instead of reusing {@code
 * UsageTracker.Stat} verbatim: this file must never need to name a {@code client.agent} type).
 *
 * <h2>What is deliberately NOT here</h2>
 * {@code numen://companion/{uuid}/inferences} and {@code numen://companion/{uuid}/region/{key}}
 * are named in the design spec but not implemented: the inference ledger has no read accessor
 * anywhere in the engine (only the write path, {@code commit_inference}, exists), and region
 * cognition lives in {@code mod/common} (a module the engine-side {@code NumenActuator} cannot
 * import — the dependency points the other way: {@code mod} depends on {@code engine}, never the
 * reverse). Neither is advertised by {@link #TEMPLATES}: listing a resource this class cannot
 * actually serve would be worse than the gap staying invisible. See the wave's followUps.
 */
final class McpResources {

    private McpResources() {}

    private static final Pattern COMPANION_URI =
            Pattern.compile("^numen://companion/([0-9a-fA-F-]{36})/(.+)$");

    /** One entry {@code resources/list} or {@code resources/templates/list} advertises. */
    record Descriptor(String uriOrTemplate, String name, String description, String mimeType) {}

    static final List<Descriptor> STATIC = List.of(
            new Descriptor("numen://companions", "companions",
                    "The owner's live companions with control state, controller, TTL, dimension and HP -- "
                            + "the same data list_companions renders, as a subscribable resource.",
                    "application/json"),
            new Descriptor("numen://protocol/world-cognition", "world-cognition-protocol",
                    "How to read the seq/schemaVersion/gameTime/provenance envelope on every event this "
                            + "server delivers, and the 'same topic, highest seq wins' trust rule. Read this "
                            + "before acting on any event notification.",
                    "text/plain")
    );

    static final List<Descriptor> TEMPLATES = List.of(
            new Descriptor("numen://companion/{uuid}/system-prompt", "companion-system-prompt",
                    "The companion's current system prompt verbatim -- a stable cache prefix, read on "
                            + "demand, never pushed.",
                    "text/plain"),
            new Descriptor("numen://companion/{uuid}/status", "companion-status",
                    "HP, dimension, control state/controller/TTL, alive/respawn countdown, game mode, "
                            + "compaction phase and last prompt token count. Subscribable.",
                    "application/json"),
            new Descriptor("numen://companion/{uuid}/control", "companion-control",
                    "Who holds this companion's lease right now, since when, and for how much longer. Subscribable.",
                    "application/json"),
            new Descriptor("numen://companion/{uuid}/landmarks", "companion-landmarks",
                    "The landmark baseline note: date, current dimension, landmarks grouped by dimension.",
                    "text/plain"),
            new Descriptor("numen://companion/{uuid}/conversation", "companion-conversation",
                    "The built-in brain's own conversation history. Only readable when the operator has "
                            + "set expose_conversation:true in mcp_server.json (off by default -- this is "
                            + "the owner's private chat).",
                    "text/plain")
    );

    /** {@code numen://usage} is listed only when the operator opted in — see {@link #listStatic}. */
    private static final Descriptor USAGE_DESCRIPTOR = new Descriptor("numen://usage", "usage",
            "Session LLM token usage, global and per-companion. Only readable when expose_usage:true.",
            "application/json");

    /** {@code resources/list} — static resources plus {@code numen://usage} when enabled. Live
     *  companions are NOT individually listed here (only their template is): the roster changes
     *  too often to enumerate as concrete {@code resources/list} rows, matching MCP's own
     *  resources-vs-templates split. */
    static List<Descriptor> listStatic(McpConfig config) {
        if (!config.exposeUsage()) return STATIC;
        List<Descriptor> out = new java.util.ArrayList<>(STATIC);
        out.add(USAGE_DESCRIPTOR);
        return out;
    }

    static JsonObject toListJson(List<Descriptor> descriptors) {
        JsonArray arr = new JsonArray();
        for (Descriptor d : descriptors) {
            JsonObject o = new JsonObject();
            o.addProperty("uri", d.uriOrTemplate());
            o.addProperty("name", d.name());
            o.addProperty("description", d.description());
            o.addProperty("mimeType", d.mimeType());
            arr.add(o);
        }
        JsonObject result = new JsonObject();
        result.add("resources", arr);
        return result;
    }

    static JsonObject toTemplatesJson(List<Descriptor> descriptors) {
        JsonArray arr = new JsonArray();
        for (Descriptor d : descriptors) {
            JsonObject o = new JsonObject();
            o.addProperty("uriTemplate", d.uriOrTemplate());
            o.addProperty("name", d.name());
            o.addProperty("description", d.description());
            o.addProperty("mimeType", d.mimeType());
            arr.add(o);
        }
        JsonObject result = new JsonObject();
        result.add("resourceTemplates", arr);
        return result;
    }

    /** Whether {@link McpServer} should honour {@code resources/subscribe} for {@code uri} — the
     *  handful of resources whose underlying data actually changes and is worth pushing {@code
     *  notifications/resources/updated} for. */
    static boolean isSubscribable(String uri) {
        if ("numen://companions".equals(uri)) return true;
        Matcher m = COMPANION_URI.matcher(uri);
        if (!m.matches()) return false;
        String tail = m.group(2);
        return tail.equals("status") || tail.equals("control");
    }

    /** Outcome of {@link #read} — exactly one of {@code text} (success) or {@code error} is non-null. */
    record ReadResult(String mimeType, String text, String error) {
        static ReadResult ok(String mimeType, String text) { return new ReadResult(mimeType, text, null); }
        static ReadResult fail(String error) { return new ReadResult(null, null, error); }
    }

    static CompletableFuture<ReadResult> read(String uri, McpConfig config) {
        if ("numen://companions".equals(uri)) {
            return NumenActuator.companions()
                    .thenApply(list -> ReadResult.ok("application/json", companionsJson(list)));
        }
        if ("numen://protocol/world-cognition".equals(uri)) {
            return CompletableFuture.completedFuture(
                    ReadResult.ok("text/plain", NumenActuator.worldCognitionProtocol()));
        }
        if ("numen://usage".equals(uri)) {
            if (!config.exposeUsage()) {
                return CompletableFuture.completedFuture(ReadResult.fail(
                        "numen://usage is disabled -- set expose_usage:true in mcp_server.json to enable it"));
            }
            return NumenActuator.usage().thenApply(u -> ReadResult.ok("application/json", usageJson(u)));
        }
        Matcher m = COMPANION_URI.matcher(uri);
        if (!m.matches()) {
            return CompletableFuture.completedFuture(ReadResult.fail("no such resource: " + uri));
        }
        UUID companion;
        try {
            companion = UUID.fromString(m.group(1));
        } catch (IllegalArgumentException bad) {
            return CompletableFuture.completedFuture(ReadResult.fail("not a valid companion uuid: " + m.group(1)));
        }
        String tail = m.group(2);
        return switch (tail) {
            case "system-prompt" -> NumenActuator.systemPrompt(companion)
                    .thenApply(s -> ReadResult.ok("text/plain", s));
            case "status" -> NumenActuator.status(companion)
                    .thenApply(s -> s == null ? ReadResult.fail("no such live companion: " + companion)
                            : ReadResult.ok("application/json", statusJson(s)));
            case "control" -> NumenActuator.status(companion)
                    .thenApply(s -> s == null ? ReadResult.fail("no such live companion: " + companion)
                            : ReadResult.ok("application/json", controlJson(s)));
            case "landmarks" -> NumenActuator.landmarks(companion)
                    .thenApply(s -> ReadResult.ok("text/plain", s));
            case "conversation" -> readConversation(companion, config);
            default -> CompletableFuture.completedFuture(ReadResult.fail("no such resource: " + uri));
        };
    }

    private static CompletableFuture<ReadResult> readConversation(UUID companion, McpConfig config) {
        if (!config.exposeConversation()) {
            return CompletableFuture.completedFuture(ReadResult.fail(
                    "numen://companion/" + companion + "/conversation is disabled -- set "
                            + "expose_conversation:true in mcp_server.json to enable it (this is the "
                            + "owner's private chat)"));
        }
        return NumenActuator.conversation(companion, 200)
                .thenApply(msgs -> ReadResult.ok("text/plain", conversationText(msgs)));
    }

    // ---- rendering: plain hand-built JSON, snake_case, independent of any DTO's field order ----

    private static String companionsJson(List<NumenActuator.Companion> list) {
        JsonArray arr = new JsonArray();
        for (NumenActuator.Companion c : list) {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", c.uuid().toString());
            o.addProperty("name", c.name());
            o.addProperty("state", c.state().name());
            o.addProperty("controller_label", c.controllerLabel());
            o.addProperty("alive", c.alive());
            o.addProperty("respawn_remaining_ms", c.respawnRemainingMs());
            o.addProperty("dimension", c.dimension());
            o.addProperty("hp", c.hp());
            o.addProperty("max_hp", c.maxHp());
            o.addProperty("ttl_remaining_ticks", c.ttlRemainingTicks());
            o.addProperty("idle_ticks", c.idleTicks());
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("companions", arr);
        return root.toString();
    }

    private static String statusJson(NumenActuator.CompanionStatus s) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", s.uuid().toString());
        o.addProperty("name", s.name());
        o.addProperty("owner_name", s.ownerName());
        o.addProperty("state", s.state().name());
        o.addProperty("controller_label", s.controllerLabel());
        o.addProperty("ttl_remaining_ticks", s.ttlRemainingTicks());
        o.addProperty("idle_ticks", s.idleTicks());
        o.addProperty("alive", s.alive());
        o.addProperty("respawn_remaining_ms", s.respawnRemainingMs());
        o.addProperty("dimension", s.dimension());
        o.addProperty("hp", s.hp());
        o.addProperty("max_hp", s.maxHp());
        o.addProperty("game_type", s.gameType());
        o.addProperty("op_enabled", s.opEnabled());
        o.addProperty("busy", s.busy());
        o.addProperty("compacting", s.compacting());
        o.addProperty("last_prompt_tokens", s.lastPromptTokens());
        return o.toString();
    }

    private static String controlJson(NumenActuator.CompanionStatus s) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", s.uuid().toString());
        o.addProperty("state", s.state().name());
        o.addProperty("controller_label", s.controllerLabel());
        o.addProperty("ttl_remaining_ticks", s.ttlRemainingTicks());
        o.addProperty("idle_ticks", s.idleTicks());
        return o.toString();
    }

    private static String usageJson(NumenActuator.UsageReport report) {
        JsonObject o = new JsonObject();
        o.add("global", statJson(report.global()));
        JsonObject per = new JsonObject();
        for (var e : report.perCompanion().entrySet()) {
            per.add(e.getKey().toString(), statJson(e.getValue()));
        }
        o.add("per_companion", per);
        return o.toString();
    }

    private static JsonObject statJson(NumenActuator.UsageStat s) {
        JsonObject o = new JsonObject();
        o.addProperty("requests", s.requests());
        o.addProperty("prompt_tokens", s.promptTokens());
        o.addProperty("completion_tokens", s.completionTokens());
        o.addProperty("cached_tokens", s.cachedTokens());
        o.addProperty("cache_hit_rate", s.cacheHitRate());
        return o;
    }

    private static String conversationText(List<NumenActuator.ConvoMessage> msgs) {
        StringBuilder sb = new StringBuilder(1 << 12);
        for (NumenActuator.ConvoMessage m : msgs) {
            sb.append('[').append(m.role());
            if (!m.toolCallId().isEmpty()) sb.append(' ').append(m.toolCallId());
            sb.append("] ").append(m.content()).append('\n');
        }
        return sb.toString();
    }
}
