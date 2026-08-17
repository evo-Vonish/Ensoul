package com.dwinovo.numen.api;

import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.prompt.NumenPrompts;
import com.dwinovo.numen.agent.skill.SkillInfo;
import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.agent.tool.ClientToolContext;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.ClientControl;
import com.dwinovo.numen.client.agent.ClientDeaths;
import com.dwinovo.numen.client.agent.ClientNumenLookup;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.agent.UsageTracker;
import com.dwinovo.numen.entity.CompanionLifecycle;
import com.dwinovo.numen.entity.ControlRegistry.ControlState;
import com.dwinovo.numen.network.payload.ControlRequestPayload;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The public entry point for driving a companion's <strong>body</strong> from
 * an outside brain â€” an MCP server, a remote agent, anything that wants to be
 * the one making the gameplay decisions instead of the companion's built-in LLM.
 *
 * <h2>Actuator â€” the outside-brain door</h2>
 * {@code NumenActuator} lets an <em>external</em> brain skip the built-in LLM
 * entirely: it lists tools, takes control of a body, calls tools directly, and
 * reads their results. The built-in brain steps aside. (Contrast the built-in
 * brain's own path, which reasons over the companion's conversation and decides
 * for itself â€” that stays in charge for chat / bridges.)
 *
 * <h2>W9 â€” server-backed lease client, per-session identity</h2>
 * Control is server-authoritative ({@code com.dwinovo.numen.entity.ControlRegistry}); this
 * class is a thin client for it, never a second source of truth. {@link #acquire} and {@link
 * #release} are real round trips â€” a {@link ControlRequestPayload} out, the answering {@link
 * com.dwinovo.numen.network.payload.ControlStatePayload} back in (via {@link ClientControl}) â€”
 * not a local boolean flip. {@link #invoke} carries the {@code sessionToken} {@link #acquire}
 * handed back and is rejected, in-process, for any caller that is not the CURRENT holder: two
 * competing external brains live in this same JVM (the MCP bridge is hosted inside the owner's
 * client), so this in-process check is what actually stops them racing each other, with the
 * server-side gate ({@code ControlRegistry.authorize}, W4) as the backstop against a stale
 * client. Acquiring a body no longer mints an {@code EntityAgentLoop} or replays its conversation
 * from disk â€” that was the exact opposite of standing the built-in brain down, and this class now
 * touches {@link AgentLoopRegistry} only via its read-only {@link AgentLoopRegistry#get} (to stop
 * a stray in-flight built-in turn on a successful acquire) and {@link
 * AgentLoopRegistry#harvestExternal} (landmark memory â€” see {@link #invoke}'s doc).
 *
 * <h2>The contract: acquire â†’ invoke* (â†’ heartbeat) â†’ release</h2>
 * Before driving a companion, {@link #acquire} it â€” this is a real request to the server; it
 * succeeds only when no other lease is already live for that body, and it is NOT idempotent
 * across different controllers: a second controller's acquire on a body someone else already
 * holds is REFUSED (never stolen) and told who holds it, in {@link AcquireResult#reason()}. Then
 * {@link #invoke} tools as needed, carrying the {@code sessionToken} {@link #acquire} returned;
 * {@link #heartbeat} periodically if the caller expects to run longer than the granted TTL. When
 * done, {@link #release} so the built-in brain (or nobody, if the owner stood it down) can act
 * again.
 *
 * <h2>Parallelism is free</h2>
 * Every call is addressed to a companion UUID, and each companion runs its own
 * body tasks independently on the server. So one external brain can {@link
 * #acquire} several companions and {@link #invoke} on each â€” they execute
 * concurrently. Mixed fleets work too: acquire A and B, leave C to its built-in
 * brain (or to a DIFFERENT external brain â€” {@link #companions} reports who
 * currently holds what, so a fleet of competing brains can triage without a
 * round trip per body).
 *
 * <h2>No conversation side effects</h2>
 * A headless {@link #invoke} does NOT touch the companion's conversation log or
 * its built-in brain's memory. The result is returned to the caller only; the
 * external brain owns the context. (The built-in brain, being paused, records
 * nothing â€” but world events that happen during takeover still accrue into its
 * context tail append-only, so it resumes with an accurate picture on release.)
 *
 * <h2>Client-side API, any thread</h2>
 * Companions are driven by their owner's game client, so this must be called in
 * the owner's client process. Every method is safe to call from any thread â€” the
 * work is marshalled onto the client main thread and a {@link CompletableFuture}
 * carries the result back.
 *
 * <h2>W11-W14/W20 â€” read-only parity surface</h2>
 * The bulk of the new methods below ({@link #status}, {@link #systemPrompt}, {@link #landmarks},
 * {@link #conversation}, {@link #usage}, {@link #skills}, {@link #worldCognitionProtocol}, {@link
 * #entityPersona}) exist so {@code mcp}'s resources/prompts surface (W13) never has to import a
 * {@code client.agent}/{@code entity} class directly â€” every one of them is a read of state that
 * already exists elsewhere, marshalled the same way every pre-existing method here is. {@link
 * #subscribeControlChanges} and {@link #cancel} are the two genuinely new pieces of behaviour:
 * the former forwards to {@link ClientControl#registerListener} (a multi-listener hook, unlike
 * {@link ClientControl#setLeaseLostListener}'s single slot, so it is safe for {@code mcp} to take
 * a slot without displacing this class's own lease-loss listener); the latter is the closest this
 * class can get to W14's "timeout/cancel must actually stop the body" requirement without
 * reaching into {@code mod/common}'s {@code CancelTasksPayload} â€” see {@link #cancel}'s own doc
 * for exactly what that costs (an unconditional {@code ownerForce=true} this class cannot turn
 * off) and why the in-process token check before firing it matters more as a result. The MCP
 * event tee itself (W12 â€” teeing {@code EntityAgentLoop.queueEventNote} to external drivers) is
 * NOT here: the one line it needs lives in {@code EntityAgentLoop.java}, which is outside this
 * wave's file set, so W12 ships as a receiving/delivery mechanism on the {@code mcp} side with no
 * producer wired up yet. See that wave's followUps for the exact one-line hook a future change
 * needs to add.
 */
public final class NumenActuator {

    /** Synthetic tool-call ids for headless invocations, disjoint from the LLM's ids. */
    private static final AtomicLong SEQ = new AtomicLong();

    /** How long {@link #acquire}/{@link #release} wait for the server's answering control-state
     *  push before giving up â€” see {@link ClientControl#awaitAcquire}. */
    private static final long ACQUIRE_RELEASE_TIMEOUT_MILLIS = 10_000L;

    /**
     * Outstanding {@link #invoke} calls per companion â€” just enough bookkeeping for {@link
     * #onLeaseLost} to fail them immediately when the lease disappears mid-call (owner
     * force-release, expiry, dismissal), instead of leaving the external caller waiting on a call
     * whose body it no longer holds. Mirrors the design doc's handover-hook step 3 ("complete
     * every in-flight MCP future... with an explanatory error result, not a bare timeout") for
     * the headless path specifically â€” the server-side task cancellation already stops the BODY;
     * this is what stops the CALLER from hanging on it. Entries are removed the moment their own
     * {@link ToolCall} completes normally. Client main thread only, like every mutation here.
     */
    private static final Map<UUID, List<CompletableFuture<String>>> OUTSTANDING = new HashMap<>();

    static {
        // The one consumer ClientControl's class doc promises W9 would be: resolve stray
        // in-flight invoke() futures the instant their lease disappears out from under them,
        // rather than let them die of invoke()'s own (nonexistent) timeout.
        ClientControl.instance().setLeaseLostListener(NumenActuator::onLeaseLost);
    }

    private NumenActuator() {}

    /**
     * One of the owner's live companions, enriched with control + life + world state so an
     * external brain can triage its fleet and see contention without a round trip per body.
     * {@code state}/{@code controllerLabel}/{@code ttlRemainingTicks}/{@code idleTicks} come from
     * {@link ClientControl} (empty label unless {@code state == EXTERNAL}); {@code alive}/{@code
     * respawnRemainingMs} from {@code ClientDeaths}; {@code dimension}/{@code hp}/{@code maxHp}
     * from a best-effort client-side entity read (see {@link #companions()}'s doc for why hunger,
     * biome, and OP flag are deliberately NOT here).
     *
     * <p><b>W14 parity row I1/L1:</b> this is what turns {@code list_companions} from "name and id
     * only" into something an agent can actually triage a fleet or see contention with.
     */
    public record Companion(UUID uuid, String name, ControlState state, String controllerLabel,
                             boolean alive, int respawnRemainingMs, String dimension, float hp,
                             float maxHp, int ttlRemainingTicks, int idleTicks) {}

    /** Outcome of {@link #acquire}: {@code ok} whether the lease was granted; {@code
     *  sessionToken} the lease token to pass to every subsequent {@link #invoke}/{@link
     *  #heartbeat}/{@link #release} call (empty when refused); {@code reason} a human-readable
     *  explanation â€” who holds it, or why the round trip failed â€” always empty when {@code ok}. */
    public record AcquireResult(boolean ok, String sessionToken, String reason) {}

    /**
     * Whether the owner is actually in a loaded world right now (W11, parity item 7 â€” the {@code
     * GET /mcp} readiness answer). Today an empty {@link #companions()} list is ambiguous: "no
     * companions summoned yet" and "the game is sitting at the main menu" render identically. This
     * disambiguates without an agent having to craft a full JSON-RPC request just to find out the
     * port answers but the game isn't in a world.
     */
    public static CompletableFuture<Boolean> worldLoaded() {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        Minecraft.getInstance().execute(() ->
                f.complete(Minecraft.getInstance().level != null && Minecraft.getInstance().player != null));
        return f;
    }

    /**
     * The owner's companions currently live in the world â€” what an external brain
     * picks from. Names are what the owner sees; the UUID is the handle for every
     * other method here.
     *
     * <p><b>W13/W14 â€” why hunger, biome, game mode's raw name, and OP-derived permissions are NOT
     * read from the entity directly:</b> {@code hp} is a {@link net.minecraft.world.entity.LivingEntity}
     * tracked-data field, synced to every observing client (the same mechanism that paints health
     * bars), so reading it off the resolved {@link AbstractClientPlayer} is honest. Hunger
     * ({@code FoodData}) is NOT â€” vanilla only replicates a player's food data to that player's
     * OWN connection, and a companion is a remote entity from this client's point of view even
     * though the owner's account drives it, so a client-side {@code getFoodData()} read would
     * silently read back a stale/default value dressed up as fact. Getting hunger, biome, and a
     * server-verified OP flag right needs the existing {@code get_self_status} tool's server round
     * trip; deliberately not duplicated here rather than shipped wrong.
     */
    public static CompletableFuture<List<Companion>> companions() {
        CompletableFuture<List<Companion>> f = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            List<Companion> out = new ArrayList<>();
            ClientControl cc = ClientControl.instance();
            for (NumenRoster.Entry e : NumenRoster.instance().entries()) {
                UUID id = e.uuid();
                ControlState state = cc.stateOf(id);
                ClientControl.Snapshot snap = cc.snapshotOf(id);
                String label = (state == ControlState.EXTERNAL && snap != null) ? snap.controllerLabel() : "";
                int ttl = snap != null ? snap.ttlRemainingTicks() : 0;
                int idle = snap != null ? snap.idleTicks() : 0;
                boolean dead = ClientDeaths.isDead(id);
                int respawnMs = dead ? (int) Math.min(Integer.MAX_VALUE, ClientDeaths.remainingMs(id)) : 0;
                AbstractClientPlayer body = ClientNumenLookup.resolve(id);
                String dimension = (body != null && body.level() != null)
                        ? body.level().dimension().identifier().toString() : "";
                float hp = body != null ? body.getHealth() : -1f;
                float maxHp = body != null ? body.getMaxHealth() : -1f;
                out.add(new Companion(id, e.name(), state, label, !dead, respawnMs, dimension, hp, maxHp, ttl, idle));
            }
            f.complete(out);
        });
        return f;
    }

    /**
     * The richer per-companion view behind {@code numen://companion/{uuid}/status} (W13, parity
     * row A4/C2/I1/J1/L1 combined): everything {@link #companions()} carries, plus {@code
     * ownerName} (this client's own player â€” companions are only ever driven by their owner's own
     * game client, so there is exactly one owner to name), {@code gameType}/{@code opEnabled} read
     * from {@link NumenRoster} (server-pushed via {@code CompanionListPayload}, so â€” unlike
     * hunger â€” these ARE trustworthy off a client-side read), and {@code busy}/{@code compacting}/
     * {@code lastPromptTokens} read off the built-in brain's own loop (read-only {@link
     * AgentLoopRegistry#get}; a companion whose built-in brain has never run once has no loop yet,
     * reported as not busy / not compacting / 0 tokens rather than an error). Completes with
     * {@code null} when {@code companion} is not (or no longer) one of the owner's live companions.
     */
    public static CompletableFuture<CompanionStatus> status(UUID companion) {
        CompletableFuture<CompanionStatus> f = new CompletableFuture<>();
        if (companion == null) {
            f.complete(null);
            return f;
        }
        Minecraft.getInstance().execute(() -> {
            NumenRoster.Entry rosterEntry = NumenRoster.instance().byUuid(companion);
            if (rosterEntry == null) {
                f.complete(null);
                return;
            }
            ClientControl cc = ClientControl.instance();
            ControlState state = cc.stateOf(companion);
            ClientControl.Snapshot snap = cc.snapshotOf(companion);
            String label = (state == ControlState.EXTERNAL && snap != null) ? snap.controllerLabel() : "";
            int ttl = snap != null ? snap.ttlRemainingTicks() : 0;
            int idle = snap != null ? snap.idleTicks() : 0;
            boolean dead = ClientDeaths.isDead(companion);
            int respawnMs = dead ? (int) Math.min(Integer.MAX_VALUE, ClientDeaths.remainingMs(companion)) : 0;
            AbstractClientPlayer body = ClientNumenLookup.resolve(companion);
            String dimension = (body != null && body.level() != null)
                    ? body.level().dimension().identifier().toString() : "";
            float hp = body != null ? body.getHealth() : -1f;
            float maxHp = body != null ? body.getMaxHealth() : -1f;
            Optional<EntityAgentLoop> loop = AgentLoopRegistry.get(companion);
            boolean busy = loop.map(EntityAgentLoop::isBusy).orElse(false);
            boolean compacting = loop.map(EntityAgentLoop::isCompacting).orElse(false);
            int tokens = loop.map(EntityAgentLoop::lastPromptTokens).orElse(0);
            // Same Entity.getName().getString() pattern EntityAgentLoop.buildStaticEnvBlock
            // already uses for this exact "owner_name" fact â€” not GameProfile, to stay consistent
            // with the one already-verified-safe accessor in this codebase.
            String ownerName = Minecraft.getInstance().player != null
                    ? Minecraft.getInstance().player.getName().getString() : "";
            // .name() (the plain enum constant name), not a display/serialized-name lookup whose
            // exact accessor on this Minecraft version can't be verified without a gradle build
            // (see the repo-wide "no gradle" rule) â€” SURVIVAL/CREATIVE/ADVENTURE/SPECTATOR is
            // unambiguous to a reading agent either way.
            f.complete(new CompanionStatus(companion, rosterEntry.name(), ownerName, state, label, ttl, idle,
                    !dead, respawnMs, dimension, hp, maxHp, rosterEntry.gameType().name(),
                    rosterEntry.opEnabled(), busy, compacting, tokens));
        });
        return f;
    }

    /** See {@link #status}. */
    public record CompanionStatus(UUID uuid, String name, String ownerName, ControlState state,
                                   String controllerLabel, int ttlRemainingTicks, int idleTicks,
                                   boolean alive, int respawnRemainingMs, String dimension, float hp,
                                   float maxHp, String gameType, boolean opEnabled, boolean busy,
                                   boolean compacting, int lastPromptTokens) {}

    /**
     * The companion's current system prompt verbatim (W13, parity row A1) â€” the same string
     * {@link EntityAgentLoop#currentSystemPrompt} hands the LLM client on every request, already
     * public and already read by {@link com.dwinovo.numen.client.agent.ContextExporter}; this just
     * gives the MCP surface the same door. Empty when no loop has been created for this companion
     * yet (the built-in brain has never run a turn) â€” read-only {@link AgentLoopRegistry#get},
     * never {@code getOrCreate}, for the same "don't replay the conversation from disk as a side
     * effect of a read" reason {@link #acquire} avoids it.
     */
    public static CompletableFuture<String> systemPrompt(UUID companion) {
        CompletableFuture<String> f = new CompletableFuture<>();
        Minecraft.getInstance().execute(() ->
                f.complete(AgentLoopRegistry.get(companion).map(EntityAgentLoop::currentSystemPrompt).orElse("")));
        return f;
    }

    /**
     * The landmark baseline note (W13, parity row D1) â€” date, current dimension, landmarks grouped
     * by dimension â€” rendered exactly as {@code recall_places}/the built-in brain's context-snapshot
     * note would show it. Routes through {@link AgentLoopRegistry#listPlaces}, the existing
     * pack-facing seam for the same data, rather than reaching {@code EntityAgentLoop}'s private
     * {@code buildContextSnapshotNote} (which is not exposed, and shouldn't be duplicated here).
     */
    public static CompletableFuture<String> landmarks(UUID companion) {
        CompletableFuture<String> f = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> f.complete(AgentLoopRegistry.listPlaces(companion)));
        return f;
    }

    /**
     * The conversation resource body (W13, parity row C1) â€” the built-in brain's own message
     * history, most-recent {@code limit} messages ({@code limit <= 0} means "all"). Gated by the
     * caller (an {@code expose_conversation} config flag, off by default â€” this is the owner's
     * private chat) rather than here, so this class stays config-agnostic. Tool-call arguments and
     * results are rendered verbatim (not summarised) so the resource is genuinely useful for
     * debugging, not just a redacted stub. Empty list when no loop exists yet.
     */
    public static CompletableFuture<List<ConvoMessage>> conversation(UUID companion, int limit) {
        CompletableFuture<List<ConvoMessage>> f = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            Optional<EntityAgentLoop> loop = AgentLoopRegistry.get(companion);
            if (loop.isEmpty()) {
                f.complete(List.of());
                return;
            }
            List<ConvoState.Msg> snap = loop.get().convo().snapshot();
            int from = (limit > 0 && snap.size() > limit) ? snap.size() - limit : 0;
            List<ConvoMessage> out = new ArrayList<>(snap.size() - from);
            for (int i = from; i < snap.size(); i++) {
                out.add(renderMsg(snap.get(i)));
            }
            f.complete(out);
        });
        return f;
    }

    /** One rendered conversation entry â€” see {@link #conversation}. {@code toolCallId} is empty
     *  except on a {@code role: tool} entry. */
    public record ConvoMessage(String role, String content, String toolCallId) {}

    private static ConvoMessage renderMsg(ConvoState.Msg msg) {
        if (msg instanceof ConvoState.Msg.User u) {
            return new ConvoMessage("user", u.content(), "");
        } else if (msg instanceof ConvoState.Msg.Assistant a) {
            StringBuilder sb = new StringBuilder(a.turn().content());
            for (var call : a.turn().toolCalls()) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append("[tool_call ").append(call.name()).append('(').append(call.arguments()).append(")]");
            }
            return new ConvoMessage("assistant", sb.toString(), "");
        } else if (msg instanceof ConvoState.Msg.Tool t) {
            return new ConvoMessage("tool", t.content(), t.toolCallId());
        }
        return new ConvoMessage("unknown", "", "");
    }

    /**
     * Session-scope LLM usage (W13, parity row J2) straight off {@link UsageTracker} â€” global
     * totals plus a per-companion breakdown. Gated by the caller (an {@code expose_usage} config
     * flag, off by default) for the same reason {@link #conversation} is: useful to an operator
     * watching cost during a filmed run, not needed to drive a body, and not this class's business
     * to decide who gets to see it.
     */
    public static CompletableFuture<UsageReport> usage() {
        CompletableFuture<UsageReport> f = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            UsageTracker t = UsageTracker.instance();
            Map<UUID, UsageStat> per = new HashMap<>();
            for (var e : t.perCompanion().entrySet()) {
                per.put(e.getKey(), toUsageStat(e.getValue()));
            }
            f.complete(new UsageReport(toUsageStat(t.global()), per));
        });
        return f;
    }

    private static UsageStat toUsageStat(UsageTracker.Stat s) {
        return new UsageStat(s.requests(), s.promptTokens(), s.completionTokens(), s.cachedTokens(), s.cacheHitRate());
    }

    /**
     * See {@link #usage}. A standalone DTO rather than {@link UsageTracker.Stat} reused verbatim â€”
     * {@code mcp} code must never need to name a {@code client.agent} type to read this class's
     * results (see the class doc's W11-W14/W20 section), and returning the internal type directly
     * would force exactly that on every caller.
     */
    public record UsageStat(int requests, long promptTokens, long completionTokens, long cachedTokens,
                             double cacheHitRate) {}

    /** See {@link #usage}. */
    public record UsageReport(UsageStat global, Map<UUID, UsageStat> perCompanion) {}

    /**
     * The installed skill catalogue, body included (W13, parity row A6) â€” backs {@code
     * prompts/list}/{@code prompts/get}. {@link SkillRegistry} is client-side and document-only
     * (no Minecraft world state), but every other read here marshals onto the main thread and its
     * class doc says "all access from the client main thread", so this does too for consistency
     * even though the risk of a bare read is low.
     */
    public static CompletableFuture<List<SkillSummary>> skills() {
        CompletableFuture<List<SkillSummary>> f = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            List<SkillSummary> out = new ArrayList<>();
            for (SkillInfo s : SkillRegistry.instance().all()) {
                out.add(new SkillSummary(s.name(), s.description() == null ? "" : s.description(), s.content()));
            }
            f.complete(out);
        });
        return f;
    }

    /** See {@link #skills}. */
    public record SkillSummary(String name, String description, String content) {}

    /**
     * {@link NumenPrompts#WORLD_COGNITION_PROTOCOL} verbatim (W13, parity row A5 â€” MANDATORY once
     * events are delivered: it is what teaches a reader how to interpret the {@code seq} /
     * {@code schemaVersion} / {@code gameTime} / {@code provenance} envelope on every event this
     * class's callers receive). A plain constant string, not tied to any Minecraft instance state,
     * so unlike everything else here this does NOT need a main-thread hop.
     */
    public static String worldCognitionProtocol() {
        return NumenPrompts.WORLD_COGNITION_PROTOCOL;
    }

    /** {@link NumenPrompts#ENTITY_PROMPT} verbatim (W13, parity row A3) â€” the persona / operating
     *  principles block, for callers (like {@code initialize}'s instructions) that want the
     *  built-in brain's own framing without composing a full per-companion system prompt. */
    public static String entityPersona() {
        return NumenPrompts.ENTITY_PROMPT;
    }

    /**
     * Register for a callback whenever the server pushes a fresh control-state snapshot (W12/W13
     * â€” backs {@code numen://companion/{uuid}/control}'s {@code resources/subscribe} and {@code
     * notifications/numen/control}). Thin forwarder to {@link ClientControl#registerListener},
     * which â€” unlike {@link ClientControl#setLeaseLostListener}'s single-hook contract â€” happily
     * takes multiple listeners; every caller here keeps its slot rather than stealing one. Fires
     * on EVERY push, including the unconditional ~20-tick keep-alive, not just on a real change â€”
     * callers that only care about material changes (see {@code McpServer}'s roster/control diff)
     * must debounce against their own last-seen snapshot themselves.
     */
    public static void subscribeControlChanges(Runnable onChange) {
        if (onChange != null) ClientControl.instance().registerListener(onChange);
    }

    /**
     * Cancel {@code companion}'s current in-flight body action WITHOUT releasing the lease (W14,
     * parity rows B2/K3) â€” "stop what you're doing, I'm still driving". Refuses (returns {@code
     * false}, no network traffic at all) unless {@code sessionToken} is verified in-process to be
     * the CURRENT live token for {@code companion}, exactly like {@link #invoke}'s own guard â€” this
     * is deliberate defense in depth, because the only reachable engine-side cancel signal, {@link
     * CompanionLifecycle#fireAbort}, fans out to {@code CoreServerTools.abort} which sends its
     * {@code CancelTasksPayload} with {@code ownerForce=true} UNCONDITIONALLY (mod/common code,
     * outside this file's reach â€” see the class doc's W14 section). Gating who may even trigger it
     * client-side is what keeps an external agent from force-cancelling a companion it does not
     * currently hold, even though the wire-level flag itself cannot be threaded through from here.
     *
     * <p>{@code CoreServerTools.abort}'s {@code IN_FLIGHT.removeIf(...)} drops the parked {@link
     * ToolCall} WITHOUT completing it (a known gap â€” see the class doc), so this method completes
     * every outstanding {@link #invoke} future for {@code companion} itself, the same way {@link
     * #onLeaseLost} already does, immediately after firing the abort â€” otherwise the MCP caller
     * would be left hanging on a call whose body just stopped executing it.
     *
     * @param reason human-readable cause, echoed into the completed calls' {@link TaskResult#fail}
     *               message (e.g. "cancelled by caller" for {@code notifications/cancelled},
     *               "call timed out" for W14's timeout-must-stop-the-body fix)
     */
    public static CompletableFuture<Boolean> cancel(UUID companion, String sessionToken, String reason) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        if (companion == null || sessionToken == null || sessionToken.isEmpty()) {
            f.complete(false);
            return f;
        }
        Minecraft.getInstance().execute(() -> {
            ClientControl cc = ClientControl.instance();
            if (cc.stateOf(companion) != ControlState.EXTERNAL || !sessionToken.equals(cc.tokenFor(companion))) {
                f.complete(false);
                return;
            }
            CompanionLifecycle.fireAbort(companion);
            failOutstanding(companion, "cancelled: "
                    + (reason == null || reason.isBlank() ? "cancelled by caller" : reason));
            f.complete(true);
        });
        return f;
    }

    /**
     * Take external control of {@code companion} â€” a real server round trip ({@link
     * ControlRequestPayload}{@code op=ACQUIRE} out, the answering control-state push back in via
     * {@link ClientControl#awaitAcquire}), NOT a local flag flip. Refused (never stolen) when
     * another lease is already live for this body; {@link AcquireResult#reason()} names the
     * current holder. Not idempotent across different controllers: calling this twice for the
     * SAME {@code controllerId}/{@code label} while already holding the lease would still just
     * re-run the round trip and get told "another brain holds it" â€” namely, this one's own prior
     * acquire â€” since {@code ControlRegistry.acquire} is a plain compare-and-set with no
     * same-caller special case.
     *
     * <p>Deliberately does NOT touch {@link AgentLoopRegistry#getOrCreate} â€” the pre-redesign
     * version of this method minted an {@code EntityAgentLoop} as a side effect, which opened the
     * conversation log and landmark store and replayed the whole conversation from disk: the
     * exact opposite of standing the built-in brain down. The only loop touch left is a
     * READ-ONLY {@link AgentLoopRegistry#get} on success, to stop a built-in turn that was
     * already mid-flight the instant before this request landed (preserves the pre-redesign
     * {@code acquireExternal}'s "stop the running internal turn" behaviour without recreating the
     * side effect it came bundled with).
     *
     * @param companion   the body to take
     * @param controllerId a stable identifier for the calling brain/session (e.g. an MCP session id)
     * @param label       human-readable identity shown on the owner's console/HUD (e.g. "Claude Code Â· 3f2a")
     * @param ttlSeconds  requested lease TTL; the server clamps it (60 s â€“ 20 min) and renews it on
     *                    every authorized call, so this only matters as a "how long before I must
     *                    heartbeat if idle" budget, not a hard ceiling on a call in progress
     */
    public static CompletableFuture<AcquireResult> acquire(UUID companion, String controllerId, String label, int ttlSeconds) {
        CompletableFuture<AcquireResult> f = new CompletableFuture<>();
        if (companion == null) {
            f.complete(new AcquireResult(false, "", "companion is required"));
            return f;
        }
        String ctrl = controllerId == null ? "" : controllerId;
        String lbl = label == null ? "" : label;
        Minecraft.getInstance().execute(() -> {
            ClientControl.instance().awaitAcquire(companion, lbl, ACQUIRE_RELEASE_TIMEOUT_MILLIS)
                    .whenComplete((outcome, err) -> {
                        if (err != null) {
                            f.complete(new AcquireResult(false, "", "acquire failed: " + err));
                            return;
                        }
                        f.complete(new AcquireResult(outcome.ok(), outcome.sessionToken(), outcome.reason()));
                        if (outcome.ok()) {
                            // Preserve the pre-redesign acquireExternal() behaviour: a built-in
                            // turn dispatched a moment before this request landed is still
                            // running (W8's gates only stop a NEW turn / a NEW tool ship) â€” stop
                            // it now that the body is genuinely ours, rather than let it waste a
                            // full LLM round trip fighting for a body it can no longer act on.
                            AgentLoopRegistry.get(companion).ifPresent(loop -> {
                                if (loop.isBusy()) loop.abort();
                            });
                        }
                    });
            Services.NETWORK.sendToServer(new ControlRequestPayload(companion, ControlRequestPayload.OP_ACQUIRE,
                    ctrl, "", lbl, ttlSeconds, (byte) 0));
        });
        return f;
    }

    /**
     * Release external control of {@code companion} â€” a real server round trip ({@code
     * op=RELEASE} out, confirmation via {@link ClientControl#awaitRelease}). Reports honestly:
     * {@code false} immediately (no network round trip at all) when this client does not
     * currently believe {@code sessionToken} is the live lease for {@code companion} â€” sending it
     * anyway would just be refused server-side (token mismatch) for the same reason. On a
     * matching token, sends the release and completes once the server's next control-state push
     * confirms the token is no longer live (someone else releasing/expiring it first counts too â€”
     * either way we no longer hold it, which is the only fact the caller needs).
     */
    public static CompletableFuture<Boolean> release(UUID companion, String sessionToken) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        if (companion == null || sessionToken == null || sessionToken.isEmpty()) {
            f.complete(false);
            return f;
        }
        Minecraft.getInstance().execute(() -> {
            if (!sessionToken.equals(ClientControl.instance().tokenFor(companion))) {
                f.complete(false);
                return;
            }
            ClientControl.instance().awaitRelease(companion, sessionToken, ACQUIRE_RELEASE_TIMEOUT_MILLIS)
                    .whenComplete((outcome, err) -> f.complete(err == null && outcome.ok()));
            Services.NETWORK.sendToServer(new ControlRequestPayload(companion, ControlRequestPayload.OP_RELEASE,
                    "", sessionToken, "", 0, (byte) 0));
        });
        return f;
    }

    /**
     * Keep the lease from expiring without invoking a tool â€” for a caller expecting to think
     * longer than the granted TTL between actions. Sends {@code op=HEARTBEAT}; the returned
     * boolean is this client's OWN belief that {@code sessionToken} is still the live lease at
     * send time (a cheap local read, not a round-trip confirmation like {@link #acquire}/{@link
     * #release} â€” a heartbeat is routine upkeep, not a decision the caller blocks on).
     */
    public static CompletableFuture<Boolean> heartbeat(UUID companion, String sessionToken) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        if (companion == null || sessionToken == null || sessionToken.isEmpty()) {
            f.complete(false);
            return f;
        }
        Minecraft.getInstance().execute(() -> {
            boolean holds = sessionToken.equals(ClientControl.instance().tokenFor(companion));
            Services.NETWORK.sendToServer(new ControlRequestPayload(companion, ControlRequestPayload.OP_HEARTBEAT,
                    "", sessionToken, "", 0, (byte) 0));
            f.complete(holds);
        });
        return f;
    }

    /**
     * Run one tool for {@code companion} directly â€” the same tools the built-in
     * brain uses (see {@link ToolRegistry#all()} for the catalogue), invoked
     * without the LLM. Perception tools resolve fast; world-action tools resolve
     * when the body finishes the task.
     *
     * <p>Requires {@code sessionToken} from a successful {@link #acquire}: before building the
     * {@link ToolCall}, checks IN-PROCESS that {@link ClientControl#tokenFor} equals it and the
     * state is {@code EXTERNAL}, failing fast with a {@link TaskResult#fail} naming the current
     * holder otherwise. This in-process check is what actually stops two competing brains in this
     * same JVM from racing each other on one body (both an MCP bridge and any other caller of
     * this API live inside the SAME owner client); the server-side {@code
     * ControlRegistry.authorize} gate (W4) is the backstop against a stale client.
     *
     * <p><b>Landmark harvest (W9):</b> a successful call's completion routes through {@link
     * AgentLoopRegistry#harvestExternal} before completing the caller's future â€” external tool
     * calls bypass the built-in dispatcher's {@code Sink} entirely, so without this a
     * place_block/interact_at done externally is invisible to landmark memory and the built-in
     * brain re-crafts/re-places duplicates after handback. Conversation-neutral: no message is
     * added anywhere (the built-in brain is paused and hears nothing from this call).
     *
     * @param companion  the body to act with
     * @param toolName   a registered tool name (case-tolerant, see {@link ToolRegistry#resolve})
     * @param argsJson   the tool's arguments as a JSON object string; null/blank means {@code {}}
     * @param sessionToken the token {@link #acquire} returned for this companion
     */
    public static CompletableFuture<String> invoke(UUID companion, String toolName, String argsJson, String sessionToken) {
        CompletableFuture<String> f = new CompletableFuture<>();
        if (companion == null || toolName == null || toolName.isBlank()) {
            f.complete(TaskResult.fail("companion and toolName are required").toJson());
            return f;
        }
        String tok = sessionToken == null ? "" : sessionToken;
        Minecraft.getInstance().execute(() -> {
            ClientControl cc = ClientControl.instance();
            if (cc.stateOf(companion) != ControlState.EXTERNAL || !tok.equals(cc.tokenFor(companion))) {
                ClientControl.Snapshot snap = cc.snapshotOf(companion);
                String holder = (cc.stateOf(companion) == ControlState.EXTERNAL && snap != null && !snap.controllerLabel().isEmpty())
                        ? snap.controllerLabel() : "nobody (built-in brain, or no brain, is driving)";
                f.complete(TaskResult.fail("you do not hold this companion â€” currently controlled by " + holder).toJson());
                return;
            }
            try {
                NumenTool tool = ToolRegistry.resolve(toolName);
                if (tool == null) {
                    f.complete(TaskResult.fail("unknown tool: " + toolName).toJson());
                    return;
                }
                AbstractClientPlayer body = ClientNumenLookup.resolve(companion);
                String id = "mcp-" + SEQ.incrementAndGet();
                String args = (argsJson == null || argsJson.isBlank()) ? "{}" : argsJson;
                trackOutstanding(companion, f);
                ToolCall call = new ToolCall(id, toolName, args,
                        new ClientToolContext(body, companion),
                        json -> {
                            untrackOutstanding(companion, f);
                            AgentLoopRegistry.harvestExternal(companion, toolName, json);
                            f.complete(json);
                        });
                tool.invoke(call);
            } catch (RuntimeException ex) {
                untrackOutstanding(companion, f);
                f.complete(TaskResult.fail(ex.getMessage()).toJson());
            }
        });
        return f;
    }

    /** {@link ClientControl.LeaseLostListener} registered once in the static initializer â€” fails
     *  every still-outstanding {@link #invoke} future for {@code companion} with an explanatory
     *  result instead of leaving the caller to discover the loss on its own. */
    private static void onLeaseLost(UUID companion, String reason) {
        failOutstanding(companion, "external control lost: " + reason);
    }

    /** Shared by {@link #onLeaseLost} and {@link #cancel} â€” complete every outstanding {@link
     *  #invoke} future for {@code companion} with a failed {@link TaskResult} carrying {@code
     *  msg}, instead of leaving the caller to time out on its own. No-op when nothing is parked. */
    private static void failOutstanding(UUID companion, String msg) {
        List<CompletableFuture<String>> calls = OUTSTANDING.remove(companion);
        if (calls == null) return;
        for (CompletableFuture<String> f : calls) {
            f.complete(TaskResult.fail(msg).toJson());
        }
    }

    private static void trackOutstanding(UUID companion, CompletableFuture<String> f) {
        OUTSTANDING.computeIfAbsent(companion, k -> new ArrayList<>()).add(f);
    }

    private static void untrackOutstanding(UUID companion, CompletableFuture<String> f) {
        List<CompletableFuture<String>> calls = OUTSTANDING.get(companion);
        if (calls == null) return;
        calls.remove(f);
        if (calls.isEmpty()) OUTSTANDING.remove(companion);
    }
}
