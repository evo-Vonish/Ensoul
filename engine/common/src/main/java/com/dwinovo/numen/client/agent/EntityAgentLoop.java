package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.llm.NumenLlmClient;
import com.dwinovo.numen.agent.llm.ConvoLog;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.agent.tool.ToolInvocation;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.platform.services.INumenConfig;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Per-entity agent loop running on the <strong>client</strong>. One instance
 * per Numen the player talks to, keyed by the stable {@code entity.getUUID()}
 * in {@link AgentLoopRegistry} and resolved to the current body via
 * {@link ClientNumenLookup} (so it survives the int-id churn of dimension
 * travel). The agent is bound to that one entity for its
 * whole lifetime — it talks directly to the owner, runs world-action tools on
 * its own body, and survives across many prompts (it is NOT a one-shot
 * sub-agent that self-destructs after a single task).
 *
 * <h2>Single-layer architecture</h2>
 * This is the deliberate roll-back from the short-lived PlayerAgent +
 * EntityAgent split. Each entity owns one conversation; the owner chats with
 * it directly (right-click → {@code EntityChatScreen}, or the Units tab Chat
 * button). The earlier two-tier design made debugging a single entity's AI
 * painful — interleaved logs, reports bouncing between agents, lifecycles
 * tearing down mid-task. Re-introduce the brain-on-the-entity model now; a
 * higher-level dispatcher can come back later once each body is solid.
 *
 * <h2>Threading rules</h2>
 * All mutations run on the client main thread:
 * <ul>
 *   <li>{@link #submitPrompt} — from {@code EntityChatScreen} (UI thread)</li>
 *   <li>tool results — routed to this loop's {@link ToolDispatcher.Sink#onResult},
 *       fed by {@link com.dwinovo.numen.agent.tool.ToolCall#complete} (e.g. from
 *       {@code TaskResultPayload.handle}, already bounced onto the main thread)</li>
 *   <li>LLM response — {@link NumenLlmClient#chatStreaming} resolves on the
 *       HTTP executor thread; {@link #bounceBackToMain} hops via
 *       {@code Minecraft.getInstance().execute} before any mutation</li>
 * </ul>
 */
public final class EntityAgentLoop {

    /**
     * Persona prompt for a single Numen body. Deliberately does NOT enumerate
     * tools — the live tool list (with full descriptions) rides along on every
     * request, and a prose copy here rotted badly once already. This prompt
     * carries only what the tool schemas can't: identity, working discipline,
     * and the voice toward the owner.
     */
    private static final String ENTITY_PROMPT = com.dwinovo.numen.agent.prompt.NumenPrompts.ENTITY_PROMPT;

    // ---- context compaction (mirrors Claude Code's /compact machinery) ----

    /**
     * The model context window now comes per-model from {@code ModelRegistry} (numen_models.json),
     * looked up from the configured provider+model at the auto-compaction gate; unknown/custom models
     * fall back to {@code ModelRegistry.DEFAULT_CTX} (64k).
     */
    /**
     * Headroom under the window at which auto-compaction fires (Claude Code's
     * {@code AUTOCOMPACT_BUFFER_TOKENS}): the next turn adds tool results and
     * a fresh system prompt on top of the last measured request, and the
     * summarization call itself must still fit.
     */
    private static final int AUTO_COMPACT_BUFFER_TOKENS = 13_000;
    /** Don't bother summarizing a conversation shorter than this. */
    private static final int MIN_COMPACT_MESSAGES = 8;
    /** Circuit breaker: stop auto-retrying after this many consecutive failures. */
    private static final int MAX_COMPACT_FAILURES = 3;

    /**
     * ICE Phase 1 — <strong>soft water line</strong> (ICE §4.4). When the last request's
     * true context size crosses this fraction of the model window, the loop dispatches a
     * background (async) compaction — the doc's "long-track" recast (§4.3, §7) — and keeps
     * running on the full context. Chosen well under the hard limit so the summary almost
     * always lands before the window − 13k blocking gate is reached.
     */
    private static final double SOFT_COMPACT_FRACTION = 0.7;
    /**
     * ICE Phase 1 — fraction of the message list to retire in one recast. The retired region
     * is {@code messages[0..cutIndex)} for the newest protocol-safe boundary at or below this
     * fraction; the remaining ~10% stays as the live tail (the reorder-buffer analogue, ICE
     * §4.1). Keeping a tail is what makes this a <em>partial</em> recast rather than the
     * whole-history blocking one.
     */
    private static final double COMPACT_CUT_FRACTION = 0.9;

    private static final String COMPACT_SYSTEM_PROMPT =
            "You are a helpful AI assistant tasked with summarizing conversations "
            + "between a Minecraft companion entity (Fenn) and its owner.";

    /**
     * The summarization request, appended as the final user message over the
     * full history. Adapted from Claude Code's compact prompt to what a
     * Minecraft body must never forget: coordinates, inventory, lessons.
     */
    private static final String COMPACT_PROMPT = """
            请将以上整段对话压缩成一份详细摘要。这份摘要将完全替代之前的对话历史，\
            成为你后续行动的唯一记忆——任何没写进摘要的信息都会永久丢失，所以请把还会用到的信息全部保留。

            按以下结构输出：
            1. 主人的指令与意图：所有明确的请求，以及当前正在执行哪一个。
            2. 世界知识：所有提到过的重要坐标（基地、传送门、熔炉、工作台、矿点、要塞等）、维度和地标。坐标数字必须逐字保留。
            3. 自身状态：最近已知的 HP、装备、背包中的关键物品及数量。
            4. 已完成的事项：按时间顺序简述。
            5. 失败与教训：失败过的操作、原因、以及学到的约束（例如某处有岩浆、某条路线不可达、某方块需要特定工具）。
            6. 待办任务：计划中尚未完成的事项及其状态。
            7. 当前工作与下一步：摘要请求前正在做什么，接下来的第一步是什么。

            只输出摘要本身。不要调用工具，不要添加摘要以外的评论。""";

    /** Wrapper that turns the raw summary into the new history's first user message. */
    private static final String SUMMARY_HEADER =
            "[对话历史已压缩] 以下是此前全部对话的摘要，请将其作为既成事实继续工作：\n\n";

    private final UUID entityUuid;
    /** JSONL persistence under {@code config/numen/conversations/<uuid>.jsonl}. */
    private final ConvoLog log;
    private final ConvoState convo;
    /**
     * L1 landmark memory (canonical disk state) + its append-only tail emitter. World
     * knowledge no longer sits in the system prefix; it accrues as {@code <landmark_event>}
     * tail notes and periodic context snapshots (append-only world-cognition design).
     */
    private final LandmarkStore landmarks;
    private final LandmarkEventEmitter landmarkEmitter;
    /**
     * True until the first per-session context snapshot has been laid down (and re-armed
     * right after each compaction). Drives the one-shot {@code context_snapshot} note that
     * gives the model the full current landmark baseline; diffs resume from there.
     */
    private boolean contextSnapshotPending = true;
    /** Session date baseline; a mid-session rollover appends a small "今天是…" tail note. */
    private java.time.LocalDate lastKnownDate;
    /**
     * §3 envelope: per-companion global monotonic event seq, persisted to the
     * {@code <uuid>.seq} sidecar next to the conversation JSONL so it survives
     * restarts (see {@link EventSeqCounter} for the persistence rationale).
     */
    private final EventSeqCounter eventSeq;
    /**
     * Prompts the owner typed while a turn was still in flight (waiting on the
     * LLM, or on outstanding tool results). They must NOT be spliced into the
     * conversation immediately: the OpenAI/DeepSeek protocol requires an
     * {@code assistant} message carrying {@code tool_calls} to be followed
     * <em>directly</em> by the matching {@code tool} results, with no
     * {@code user} message in between. So we hold them here and flush them in
     * at the next protocol-valid point (see {@link #flushBufferedPrompts}).
     */
    private final List<String> bufferedPrompts = new ArrayList<>();
    /**
     * Image attachment file paths for the buffered owner prompt(s), accumulated
     * in lock-step with {@link #bufferedPrompts} and flushed onto the single
     * merged user message. Only owner prompts carry attachments; injected events
     * and death notes use the text-only path, contributing nothing here.
     */
    private final List<String> bufferedAttachments = new ArrayList<>();

    private boolean awaitingLlmResponse = false;
    private boolean aborted = false;

    /**
     * Set while an external driver (an MCP client via
     * {@link com.dwinovo.numen.api.NumenActuator}) holds this body. The built-in brain is
     * paused — no LLM turn starts — until {@link #releaseExternal}. Distinct from
     * {@link #dead} (body gone) and {@link #aborted} (owner stopped one turn): this is a
     * deliberate hand-off of the whole body to an outside brain. World events still queue
     * into the context tail while controlled (append-only), they just don't wake the paused
     * brain — so it resumes with an accurate picture on release.
     */
    private boolean externallyDriven = false;

    /**
     * Runs this turn's tool calls one at a time and reports each result back
     * through a {@link ToolDispatcher.Sink} into the conversation. All the
     * tool-execution plumbing (serial queue, ship-to-server, completion,
     * timeout) lives in here, not in the loop.
     */
    private final ToolDispatcher dispatcher;

    /** A summarization call is in flight; blocks normal turns until it lands. */
    private boolean compacting = false;
    /** Context size of the last request as the API counted it (0 = unknown yet). */
    private int lastPromptTokens = 0;
    /** Consecutive compaction failures — circuit breaker for the auto path. */
    private int compactFailures = 0;

    // ---- ICE Phase 1: asynchronous (long-track) compaction ----
    //
    // The retired region [0..cutIndex) is summarized by a background HTTP call while the
    // live loop keeps running on the FULL context. When the summary lands it is spliced in
    // at the next turn boundary: new history = [summary] + messages[cutIndex..] AS THEY ARE
    // NOW (kept tail + everything appended during the recast). Unlike the blocking path this
    // never freezes the loop. State machine: IDLE → (soft trigger) IN_FLIGHT → (result)
    // READY → (next turn boundary) IDLE. Guard: at most one recast in flight.

    /** ICE: a background (async) compaction HTTP call is outstanding. Does NOT make the loop busy. */
    private boolean asyncCompacting = false;
    /** Cut index snapshotted when the in-flight async compaction was dispatched (−1 = none). */
    private int asyncCutIndex = -1;
    /** {@link ConvoState#structuralEpoch()} at dispatch — the retired region's identity token (−1 = none). */
    private long asyncEpoch = -1;

    /**
     * A completed async summary staged for the splice, waiting for the next turn boundary
     * (null = none). Held raw; the {@link #SUMMARY_HEADER} wrap + landmark snapshot fold are
     * done at apply time so the post-recast baseline is current.
     */
    private String readyAsyncSummary = null;
    /** Cut index for the staged {@link #readyAsyncSummary}. */
    private int readyAsyncCutIndex = -1;
    /** Structural epoch captured for the staged {@link #readyAsyncSummary} — re-checked at splice. */
    private long readyAsyncEpoch = -1;

    /**
     * Set when the hard limit was reached while an async compaction was still in flight and
     * the loop FROZE to wait for it (rather than dispatching a second). Tells the async
     * completion path that a turn is pending and must be resumed once the summary is spliced
     * (or, on async failure, that the blocking fallback must run now). Mirrors the {@code auto}
     * flag of the blocking path: "a turn was interrupted; resume it."
     */
    private boolean resumeTurnAfterAsync = false;

    /**
     * Set while the body is DEAD and awaiting its timed respawn (see {@link #onEntityDied} /
     * {@link #onRespawned}). The loop is frozen — no LLM turn starts — until the body comes back.
     */
    private boolean dead = false;

    /** Death cause recorded at death, replayed in the respawn event (null while alive). */
    private String deathCause;
    /** Tool calls that were in flight when the body died — resolved on respawn, not before. */
    private List<String> deathInterruptedCalls = List.of();

    /**
     * §3 event/note tail entries carried across a death freeze and re-queued on respawn.
     * A pack producer emits a death-time {@code <system_notice>} from
     * {@code CompanionLifecycle.onDeath} — fired <em>before</em> the death payload — so the
     * note is either already sitting in {@link #bufferedPrompts} when {@link #onEntityDied}
     * runs, or arrives at {@link #injectEvent} just after {@code dead} latches. Either way the
     * pre-fix code lost it (blanket {@code bufferedPrompts.clear()} / the {@code dead} guard).
     * Death-time notices are meant to ride the RESPAWN turn, so we stash event/note entries
     * here and re-queue them in {@link #onRespawned}. Owner prompts are NOT carried (discarded
     * at death, as before). Main-thread only.
     */
    private final List<String> pendingPostThaw = new ArrayList<>();

    /**
     * Bumped every time the owner interrupts a turn ({@link #abort}). Each LLM
     * dispatch captures the value at send time; when the streamed response
     * lands {@link #handleResponse} discards it if the generation no longer
     * matches — i.e. the turn it belongs to was cancelled. This is the
     * equivalent of Claude Code spinning up a fresh {@code AbortController} per
     * turn: an in-flight HTTP response from an interrupted turn must never be
     * spliced back into the conversation or dispatch its tool calls.
     *
     * <p>Also bumped by an <em>emergency preemption</em> ({@link #preemptWithEmergencyTurn}):
     * when an urgent event arrives while a slow think is streaming, we supersede that
     * think with a fast emergency turn. {@code volatile} because the gated
     * {@code onReasoning} callback reads it from the HTTP thread to decide whether its
     * stream still owns the live-reasoning display.
     */
    private volatile int turnGeneration = 0;

    /**
     * Set true when an urgent injected event ({@link #injectEvent} with {@code urgent})
     * is what will start the next turn — so that turn runs at emergency effort. Remembers
     * <em>why</em> a turn started across the buffer/flush boundary (the urgent flag itself
     * is otherwise consumed by {@link #tryStartTurn} only as a wake-up). Consumed (cleared)
     * the moment the emergency turn is dispatched: tool-chain continuations after it are
     * ordinary turns at the base effort.
     */
    private boolean pendingUrgent = false;

    /**
     * Emergency refractory latch: true while the LLM response currently awaited belongs to an
     * EMERGENCY-classified turn. While set, urgent events must NOT preempt — the emergency turn
     * is already racing toward a reaction, and superseding it restarts the latency clock. Under
     * a sustained attack (a zombie biting every 1.5–3 s) preempt-on-every-hit livelocks: each
     * response lands just after the next preemption superseded it, its tool calls are discarded,
     * and zero combat actions ever dispatch (field incident: gens 0→6 in 9 s, five correct
     * [equip_item, hunt] decisions all wasted, companion died). Urgents arriving during the
     * refractory buffer normally and keep {@link #pendingUrgent} set, so the FOLLOW-UP turn is
     * emergency-classified too. Lifecycle mirrors {@link #awaitingLlmResponse} exactly: set at
     * dispatch, cleared when the awaited response lands (success or failure), on owner abort,
     * and on death freeze — no path leaves it stuck.
     */
    private boolean awaitingEmergency = false;

    /** At most one preemption per this many client ticks, whatever the turn classification (3 s). */
    private static final int PREEMPT_COOLDOWN_TICKS = 60;

    /** Client ticks seen by this loop (bumped in {@link #clientTick}) — the cooldown clock. */
    private long clientTicks = 0;

    /**
     * Tick of the last preemption — the cooldown backstop against cross-kind urgent storms
     * (e.g. hurt + creeper alarms interleaved) where each kind alone would pass the refractory
     * check. Initialised to {@code -PREEMPT_COOLDOWN_TICKS} so the first preemption is never
     * blocked (and no {@code Long.MIN_VALUE} subtraction overflow).
     */
    private long lastPreemptTick = -PREEMPT_COOLDOWN_TICKS;

    /**
     * Generations superseded by an emergency preemption whose in-flight response we still
     * want to <em>harvest</em> (its accumulated reasoning + any final text) when it lands,
     * as opposed to an owner-/death-interrupt where the discarded response is simply dropped.
     * {@link #handleResponse} removes the entry as it harvests. Main-thread only.
     */
    private final Set<Integer> preemptedGenerations = new HashSet<>();

    /** Cap on the harvested reasoning tail folded into the next turn's context note (chars). */
    private static final int HARVEST_REASONING_CAP = 2000;

    /** Assistant-message extras keys under which backends stream their reasoning transcript. */
    private static final String[] REASONING_EXTRA_KEYS = {"reasoning_content", "reasoning"};

    /**
     * The current turn's live reasoning transcript, streamed in from the LLM's
     * reasoning channel (DeepSeek {@code reasoning_content}, GLM {@code reasoning},
     * ...) via the {@code onReasoning} callback and rendered live by the chat
     * panel. Written whole-String from the HTTP thread (hence {@code volatile});
     * display-only — never added to the conversation, persisted, or re-sent.
     * Cleared at every turn boundary (response landed, interrupt, death).
     */
    private volatile String liveReasoning = "";

    /** Keep at most this many recent turns' reasoning transcripts for the panel's fold-open browsing. */
    private static final int MAX_TURN_REASONING = 20;

    /**
     * Display-only reasoning transcripts of recently completed turns, keyed by the assistant
     * message's index in the convo snapshot at the moment it was appended. Indices stay valid
     * because ConvoState history is append-only between compactions; compaction ({@code replaceAll})
     * renumbers everything, so the map is cleared there. Never persisted (ConvoLog untouched) and
     * never sent on the wire — pure UI state, bounded at {@link #MAX_TURN_REASONING} entries
     * (eldest evicted first). Main-thread only, like all other loop mutations.
     */
    private final java.util.LinkedHashMap<Integer, String> turnReasoning =
            new java.util.LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<Integer, String> eldest) {
                    return size() > MAX_TURN_REASONING;
                }
            };

    EntityAgentLoop(UUID entityUuid) {
        this.entityUuid = entityUuid;
        Path numenRoot = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("numen");
        this.log = ConvoLog.forEntity(numenRoot.resolve("conversations"), entityUuid);
        this.convo = new ConvoState(log::append);
        this.eventSeq = EventSeqCounter.forEntity(numenRoot.resolve("conversations"), entityUuid);
        this.landmarks = LandmarkStore.forEntity(numenRoot.resolve("memory"), entityUuid);
        // The emitter appends landmark events onto the same buffered user-role tail the
        // owner's prompts ride (through the stamping choke point, so they carry the §3
        // envelope), splicing in at the next protocol-valid boundary.
        this.landmarkEmitter = new LandmarkEventEmitter(this::queueEventNote);
        this.dispatcher = new ToolDispatcher(entityUuid, new ToolDispatcher.Sink() {
            @Override public void onResult(ToolInvocation inv, String resultJson) {
                harvestLandmarks(inv.name(), resultJson);
                convo.addToolResult(inv.id(), resultJson);
            }
            @Override public void onAllSettled() {
                tryStartTurn();
            }
            @Override public AbstractClientPlayer entity() {
                return resolveEntity();
            }
        });
        restoreFromDisk();
    }

    /**
     * Replay the persisted conversation tail into memory and heal whatever a
     * dead session left dangling, so the first request after a relaunch is
     * protocol-valid:
     * <ul>
     *   <li>assistant tool_calls whose results never arrived (the game closed
     *       mid-task) get synthetic "interrupted" results — the same trick as
     *       the owner's Stop button; the synthetic results also append to the
     *       file, healing it on disk;</li>
     *   <li>a trailing user message (closed while waiting on the LLM) is
     *       capped with a short assistant note, mirroring {@link #abort}, so
     *       the next prompt doesn't create back-to-back user messages.</li>
     * </ul>
     */
    private void restoreFromDisk() {
        List<ConvoState.Msg> history = log.load(ConvoLog.DEFAULT_LOAD_LIMIT);
        if (history.isEmpty()) return;
        convo.preload(history);

        List<String> dangling = ConvoLog.unansweredToolCallIds(history);
        for (String id : dangling) {
            convo.addToolResult(id,
                    "{\"success\":false,\"message\":\"interrupted: the game was closed before this finished\"}");
        }
        if (convo.lastMessage() instanceof ConvoState.Msg.User) {
            convo.addAssistant(new AssistantTurn("(已中断)", List.of(), null));
        }
        Constants.LOG.info("[numen-entity#{}] restored {} msg(s) from disk{}",
                entityUuid, history.size(),
                dangling.isEmpty() ? "" : " (healed " + dangling.size() + " dangling tool call(s))");
    }

    public UUID entityUuid() { return entityUuid; }
    public ConvoState convo() { return convo; }

    /**
     * Release transient per-loop state when this loop is permanently dropped (dismiss / unload
     * without a respawn) — called by {@link AgentLoopRegistry#dispose}. Clears the death-carry
     * queue so notes stashed for a respawn that will never come don't linger. The loop object is
     * itself removed from the registry and GC'd, so this is belt-and-suspenders, but it keeps the
     * "carried across death" contract explicit.
     */
    public void dispose() {
        pendingPostThaw.clear();
    }
    /** Context size of the last request as the API counted it (0 = unknown yet) — read by the chat panel's ctx bar. */
    public int lastPromptTokens() { return lastPromptTokens; }

    /** The current turn's live reasoning transcript ({@code ""} when none) — read by the chat panel. */
    public String liveReasoning() { return liveReasoning; }

    /**
     * Reasoning transcript stored for the assistant message at snapshot index {@code msgIndex}
     * ({@code null} when none / evicted / pre-compaction). Read by the chat panel's fold rows.
     */
    public String reasoningAt(int msgIndex) { return turnReasoning.get(msgIndex); }

    /** The exact system prompt a turn dispatched right now would carry — read by the context exporter. */
    public String currentSystemPrompt() {
        return composeSystemPrompt(Services.CONFIG.getSystemPrompt());
    }

    /** Owner typed a prompt in the chat GUI (text only). */
    public void submitPrompt(String text) {
        submitPrompt(text, List.of());
    }

    /**
     * Owner sent a prompt from the chat GUI, optionally with image attachments
     * (absolute paths to persisted image files). The attachments ride along with
     * the text on the same merged user message (see {@link #flushBufferedPrompts}).
     */
    public void submitPrompt(String text, List<String> attachments) {
        if (dead) {
            Constants.LOG.info("[numen-entity#{}] prompt ignored — body is dead", entityUuid);
            return;
        }
        boolean wasAborted = aborted;
        aborted = false;
        // Always buffer first; tryStartTurn() splices buffered prompts into the
        // conversation only at a protocol-valid point. If we're mid-turn (the
        // guards in tryStartTurn fire), the prompt stays buffered and gets
        // flushed once the outstanding assistant/tool round-trip completes —
        // this avoids inserting a user message between assistant(tool_calls)
        // and its tool results (which the API rejects with HTTP 400).
        boolean deferred = awaitingLlmResponse || dispatcher.busy();
        bufferedPrompts.add(text);
        if (attachments != null && !attachments.isEmpty()) bufferedAttachments.addAll(attachments);
        Constants.LOG.info("[numen-entity#{}] user prompt ({} chars, {} image(s)){}{}: {}",
                entityUuid, text.length(),
                attachments == null ? 0 : attachments.size(),
                wasAborted ? " — reset previous abort" : "",
                deferred ? " — buffered (mid-turn)" : "",
                truncate(text, 200));
        tryStartTurn();
    }

    /** Driven once per client tick (see {@code AgentLoopRegistry.tickAll}) — backstop timeout + cooldown clock. */
    public void clientTick() {
        clientTicks++;
        dispatcher.tick();
    }

    /**
     * Pull functional-block coordinates out of successful tool results into the
     * {@link LandmarkStore}. The results already carry them — place_block reports the
     * block it placed, interact_at reports the station it activated (a chest/furnace/table
     * it opened) — this just stops the loop from forgetting them once the result scrolls
     * out of context. Both tools report the same {@code block} + {@code x/y/z} shape;
     * {@code landmarks.record} filters to tracked station types (non-stations fall away),
     * updates disk-only on a re-use, and queues an {@code added}/{@code repurposed} event
     * for a genuine change — emitted at the next turn boundary.
     */
    private void harvestLandmarks(String toolName, String resultJson) {
        try {
            JsonObject root = JsonParser.parseString(resultJson).getAsJsonObject();
            if (!root.has("success") || !root.get("success").getAsBoolean()) return;
            JsonObject data = root.has("data") && root.get("data").isJsonObject()
                    ? root.getAsJsonObject("data") : null;
            if (data == null) return;

            switch (toolName) {
                case "place_block", "interact_at" -> {
                    if (data.has("block") && data.has("x")) {
                        String kind = data.get("block").getAsString();   // full block id, e.g. minecraft:furnace
                        AbstractClientPlayer body = resolveEntity();
                        String dim = body != null
                                ? body.level().dimension().identifier().toString() : null;
                        landmarks.record(kind, new net.minecraft.core.BlockPos(
                                data.get("x").getAsInt(),
                                data.get("y").getAsInt(),
                                data.get("z").getAsInt()), dim);
                    }
                }
                default -> { /* nothing to harvest */ }
            }
        } catch (RuntimeException ex) {
            Constants.LOG.debug("[numen-entity#{}] landmark harvest skipped: {}",
                    entityUuid, ex.toString());
        }
    }

    // ---- interrupt (owner-triggered, from the chat GUI "Stop" button) ----

    /** A turn is actively running: waiting on the LLM, on tool results, or compacting. */
    public boolean isBusy() {
        return awaitingLlmResponse || compacting || dispatcher.busy();
    }

    /** A summarization call is currently in flight (drives the GUI status line). */
    public boolean isCompacting() {
        return compacting;
    }

    /** Manual compaction is actionable right now (drives the Compact button). */
    public boolean canCompact() {
        return !dead && !isBusy() && convo.snapshot().size() >= MIN_COMPACT_MESSAGES;
    }

    /** Owner prompts are queued, waiting to flush into the conversation. */
    public boolean hasQueuedPrompts() {
        return !bufferedPrompts.isEmpty();
    }

    /** There is something an interrupt would act on — drives the Stop button's enabled state. */
    public boolean canInterrupt() {
        return isBusy() || hasQueuedPrompts();
    }

    /**
     * Owner-triggered interrupt — the chat GUI's "Stop" button. Mirrors Claude
     * Code's {@code handleCancel} (useCancelRequest.ts) two-priority rule:
     *
     * <ol>
     *   <li><b>A turn is in flight</b> → stop it. The in-flight LLM response is
     *       invalidated via {@link #turnGeneration} (discarded when it lands, so
     *       it can't dispatch tools after the fact); any world-action tool calls
     *       still awaiting a server result get a synthetic "interrupted" result
     *       so every {@code assistant(tool_calls)} keeps matching {@code tool}
     *       results and the next request stays protocol-valid. A
     *       {@code CancelTasksPayload} also ships to the server so the
     *       <em>body</em> stops too — without it the entity keeps walking/mining
     *       to its task deadline while only the conversation halts. Queued
     *       prompts are <em>preserved</em> — they flush on the next submit,
     *       exactly like Claude Code keeps its message queue across an
     *       interrupt.</li>
     *   <li><b>Idle but prompts are queued</b> (e.g. typed during a turn that was
     *       just interrupted and is now held) → drop the queue. Mirrors
     *       {@code popCommandFromQueue} when there's no running task to cancel.</li>
     * </ol>
     *
     * No-op when nothing is running and nothing is queued.
     */
    public void abort() {
        if (isBusy()) {
            // Priority 1: stop the running turn (or the in-flight compaction —
            // its response is generation-stamped too, so it gets discarded).
            turnGeneration++; // any in-flight LLM response is now stale → discarded on arrival
            preemptedGenerations.clear(); // owner interrupt → drop any pending harvest, don't fold it in
            pendingUrgent = false;        // an owner interrupt cancels a pending urgent classification
            resumeTurnAfterAsync = false; // no frozen turn to resume once the async recast lands
            boolean wasAwaitingLlm = awaitingLlmResponse;
            awaitingLlmResponse = false;
            awaitingEmergency = false;   // interrupt ends any emergency refractory
            compacting = false;
            liveReasoning = ""; // the interrupted turn's live thinking is gone too

            // Synthesize cancelled results for EVERY outstanding call (in flight AND
            // still-queued) so the assistant(tool_calls) message keeps matching tool
            // results — otherwise the next request is protocol-invalid (HTTP 400). Real
            // results arriving later are dropped as "late" by the dispatcher.
            List<String> cancelled = dispatcher.cancelAndDrain();
            for (String id : cancelled) {
                convo.addToolResult(id,
                        "{\"success\":false,\"message\":\"interrupted by owner\"}");
            }

            // Stop the BODY too, not just the conversation: cancelAndDrain fires
            // CompanionLifecycle.onAbort, which tool packs subscribe to so they can
            // halt their own server-side work. The engine sends no packet itself.

            // If we cut off an in-flight LLM call before its assistant turn was
            // recorded, the conversation now ends on a user message. Cap it with a
            // short assistant note so the next prompt doesn't create back-to-back
            // user messages (some backends reject those — see flushBufferedPrompts).
            if (wasAwaitingLlm && cancelled.isEmpty()
                    && convo.lastMessage() instanceof ConvoState.Msg.User) {
                convo.addAssistant(new AssistantTurn("(已中断)", List.of(), null));
            }

            convo.resetTurnCount();
            aborted = true;
            Constants.LOG.info("[numen-entity#{}] interrupted by owner (awaitingLlm={}, cancelledTools={}, queued={})",
                    entityUuid, wasAwaitingLlm, cancelled.size(), bufferedPrompts.size());
        } else if (!bufferedPrompts.isEmpty()) {
            // Priority 2: idle — drop the held queue.
            int dropped = bufferedPrompts.size();
            bufferedPrompts.clear();
            bufferedAttachments.clear();
            Constants.LOG.info("[numen-entity#{}] interrupt cleared {} queued prompt(s)",
                    entityUuid, dropped);
        }
    }

    // ---- external control (an MCP client / Claude drives the body directly, via NumenActuator) ----

    /**
     * An external driver takes control of this body ({@link com.dwinovo.numen.api.NumenActuator#acquire}).
     * Pauses the built-in brain (no LLM turn starts) and stops any in-flight internal turn/task —
     * reusing the owner-interrupt path {@link #abort()} when one is running, which heals the
     * conversation to stay protocol-valid, preserves queued prompts/events, and fires
     * {@code CompanionLifecycle.onAbort} so the body itself stops. When the loop is idle there is
     * nothing to stop, so no interrupt is issued (and queued prompts/events are left untouched).
     * Idempotent. Client main thread only.
     */
    public void acquireExternal() {
        if (externallyDriven) return;
        externallyDriven = true;
        if (isBusy()) abort();   // stop the running internal turn + free the body; queued prompts survive
        Constants.LOG.info("[numen-entity#{}] external control acquired — built-in brain paused", entityUuid);
    }

    /**
     * The external driver released control ({@link com.dwinovo.numen.api.NumenActuator#release}) —
     * the built-in brain may act again. Does not auto-start a turn; it waits for the next owner
     * prompt or event (any world events that queued during takeover flush on that turn).
     * Idempotent.
     */
    public void releaseExternal() {
        if (!externallyDriven) return;
        externallyDriven = false;
        aborted = false;   // clear the abort latch acquireExternal may have set, so the brain can resume
        Constants.LOG.info("[numen-entity#{}] external control released — built-in brain resumed", entityUuid);
    }

    /** True while an external driver (MCP / Claude via NumenActuator) holds this body. */
    public boolean isExternallyDriven() {
        return externallyDriven;
    }

    /**
     * The body died — the server tells us via {@code NumenDeathPayload} with the death cause. SUSPEND
     * (not dispose): the companion respawns at its owner shortly and {@link #onRespawned} resumes us.
     * Discard any in-flight LLM turn (bump {@link #turnGeneration}), then heal the conversation so it
     * stays protocol-valid AND the brain learns why it stopped — resolve every in-flight tool call with
     * the death cause, and cap a trailing user message (mirrors {@link #restoreFromDisk}). Latch
     * {@link #dead} so no turn starts until respawn.
     */
    public void onEntityDied(String cause) {
        // FREEZE hard: stop all LLM output/work and feed the model NOTHING now (adding a tool result
        // here would let the loop continue). Just record what was in flight + the cause; everything is
        // restored on respawn. The body is gone, so its tool results will never arrive — we'll synth
        // them at respawn instead.
        deathCause = cause;
        // Resolve at respawn: every outstanding call (in flight + still queued) — all
        // are listed in the assistant message, so all need results.
        deathInterruptedCalls = dispatcher.cancelAndDrain();
        turnGeneration++;          // discard any in-flight LLM response (halt output)
        preemptedGenerations.clear(); // death drops any pending harvest — the loop is frozen
        pendingUrgent = false;
        awaitingLlmResponse = false;
        compacting = false;
        resumeTurnAfterAsync = false;   // any turn frozen waiting on an async recast is abandoned by death
        liveReasoning = "";        // the dead turn's live thinking is gone too
        // Carry queued EVENT/NOTE entries across the freeze (e.g. a death-time <system_notice>
        // a pack producer emitted from onDeath just before this payload) so they ride the
        // respawn turn instead of being wiped. Owner prompts are still discarded here (their
        // image attachments too) — an owner directive typed mid-death has no meaning post-respawn.
        int carried = 0;
        for (String queued : bufferedPrompts) {
            if (isEventNote(queued)) {
                pendingPostThaw.add(queued);
                carried++;
            }
        }
        bufferedPrompts.clear();
        bufferedAttachments.clear();
        if (carried > 0) {
            Constants.LOG.info("[numen-entity#{}] {} note(s) carried across death freeze", entityUuid, carried);
        }
        dead = true;
        Constants.LOG.info("[numen-entity#{}] body died ({}) — loop frozen ({} call(s) in flight)",
                entityUuid, cause, deathInterruptedCalls.size());
    }

    /**
     * The body respawned at its owner after dying — thaw the frozen loop and ONLY NOW restore context:
     * resolve any tool call that was interrupted by the death (so the conversation is valid and the
     * brain learns its task was cut short), then inject a {@code <event>} detailing the death cause.
     * Nothing was fed to the model while dead, so it stayed fully stopped for the whole timer.
     */
    public void onRespawned(String payloadCause) {
        boolean wasFrozen = dead;                 // same-session death (mid-task) vs a fresh loop after relog
        dead = false;
        if (wasFrozen) {
            for (String id : deathInterruptedCalls) {
                convo.addToolResult(id, TaskResult.fail("任务因你死亡而中断").toJson());
            }
            deathInterruptedCalls = List.of();
            if (convo.lastMessage() instanceof ConvoState.Msg.User) {
                convo.addAssistant(new AssistantTurn("(已中断)", List.of(), null));
            }
        }
        // Prefer the cause carried by the respawn payload (survives a logout that cleared deathCause).
        String raw = (payloadCause != null && !payloadCause.isBlank()) ? payloadCause
                : (deathCause != null ? deathCause : "未知原因");
        String cause = raw.replace('<', '(').replace('>', ')');
        deathCause = null;
        Constants.LOG.info("[numen-entity#{}] respawned ({}) — loop thawed", entityUuid, cause);
        // urgent only when it died mid-task (react now); a fresh post-login revival waits for the owner.
        injectEvent("<event kind=\"death\">你刚才死了(" + cause
                + "),物品掉落在死亡地点,手头的任务中断了;现已在主人身边复活。先看看状况,继续或重新规划。</event>", wasFrozen);
        // Re-queue any death-time notices carried across the freeze, in original order, AFTER the
        // engine's death <event>. They ride the same respawn turn (the death event above is urgent
        // when wasFrozen, so a turn will run and flush the whole merged tail). queueEventNote is
        // idempotent for already-stamped notes and stamps the (rare) unstamped one, so identity/seq
        // are preserved.
        // pendingPostThaw is only ever populated while dead (onEntityDied / the dead-guard below),
        // so a non-empty list implies wasFrozen: the urgent death <event> above already drives the
        // respawn turn that flushes these. No extra nudge needed.
        if (!pendingPostThaw.isEmpty()) {
            Constants.LOG.info("[numen-entity#{}] re-queuing {} carried note(s) onto the respawn turn",
                    entityUuid, pendingPostThaw.size());
            for (String note : pendingPostThaw) {
                queueEventNote(note);
            }
            pendingPostThaw.clear();
        }
    }

    /**
     * Inject an asynchronous world event into the conversation (dimension change, hazard, …) — the
     * generic version of the Claude-Code "channel notification": the event rides the same buffered
     * queue as owner prompts, so it splices in only at a protocol-valid boundary. {@code urgent} wakes
     * an idle brain to react now; otherwise it sits in the queue and the brain sees it on the next
     * owner-driven turn (no extra LLM call, no unprompted chatter). Dropped while frozen by death.
     */
    public void injectEvent(String xml, boolean urgent) {
        if (dead) {
            // Frozen by death, but a death-time notice must not be lost: stash event/note XML to
            // ride the respawn turn (see pendingPostThaw / onRespawned). Non-note text is still
            // dropped — nothing can be fed to the model while the body is dead. Urgency is moot
            // here; the respawn <event> carries the wake-up.
            if (isEventNote(xml)) {
                pendingPostThaw.add(xml);
                Constants.LOG.info("[numen-entity#{}] event carried across death freeze (dead): {}",
                        entityUuid, truncate(xml, 120));
            }
            return;
        }
        queueEventNote(xml);
        Constants.LOG.info("[numen-entity#{}] event queued{}: {}",
                entityUuid, urgent ? " (urgent)" : "", truncate(xml, 120));
        if (urgent) {
            // Remember this turn is urgent-triggered → it runs at emergency effort, even
            // if it can't start until a mid-flight tool chain / compaction settles.
            pendingUrgent = true;
            if (awaitingLlmResponse && !dispatcher.busy() && !compacting) {
                if (awaitingEmergency) {
                    // REFRACTORY: an emergency turn is already racing toward a reaction.
                    // Preempting it would restart the latency clock — under a sustained
                    // attack that livelocks (every response lands pre-superseded, its
                    // tools discarded, and no action ever executes). Buffer instead;
                    // pendingUrgent stays set so the follow-up turn is emergency too.
                    Constants.LOG.info("[numen-entity#{}] urgent buffered (emergency turn in flight, gen {})",
                            entityUuid, turnGeneration);
                } else if (clientTicks - lastPreemptTick < PREEMPT_COOLDOWN_TICKS) {
                    // COOLDOWN backstop: at most one preemption per window, whatever the
                    // in-flight turn's classification (covers cross-kind urgent storms).
                    Constants.LOG.info("[numen-entity#{}] urgent buffered (preempt cooldown, {} tick(s) left)",
                            entityUuid, PREEMPT_COOLDOWN_TICKS - (clientTicks - lastPreemptTick));
                } else {
                    // A slow BASE think is streaming and nothing has been appended for it
                    // yet: supersede it NOW with a fast emergency turn rather than waiting
                    // it out (first-response latency matters).
                    preemptWithEmergencyTurn();
                }
            } else {
                tryStartTurn();
            }
        }
    }

    // ---- §3 event envelope (the client convergence point) ----

    /**
     * The single choke point where event/note XML strings enter the buffered tail:
     * stamps the §3 envelope ({@code seq} + {@code schemaVersion}, plus {@code gameTime}
     * when the body's level is resolvable) into the root tag, then queues the note.
     * Plain text (an owner prompt never routes here, but defensively) and already-stamped
     * roots pass through untouched. {@code provenance} is left entirely to producers —
     * absence means "observed" per the protocol. Returns the assigned seq, or {@code -1}
     * when the note was not stampable.
     */
    private long queueEventNote(String note) {
        long seq = -1L;
        if (EventEnvelope.isStampable(note)) {
            seq = eventSeq.next();
            note = EventEnvelope.stamp(note, seq, currentGameTime());
        }
        bufferedPrompts.add(note);
        return seq;
    }

    /** Stamp a note without queueing it — for notes placed specially (index-0 snapshot, compaction fold). */
    private String stampIfEvent(String note) {
        return EventEnvelope.isStampable(note)
                ? EventEnvelope.stamp(note, eventSeq.next(), currentGameTime())
                : note;
    }

    /**
     * The world's game time via the resolved client body, or {@code null} when the body
     * is unloaded this instant (then the envelope simply omits {@code gameTime} — it is
     * an optional envelope field, not worth a lookup fallback). Cheap: client levels
     * carry the synced game time locally ({@code LevelAccessor.getGameTime()}).
     */
    private Long currentGameTime() {
        AbstractClientPlayer body = resolveEntity();
        return body != null ? body.level().getGameTime() : null;
    }

    /**
     * §7 INFERENCE seam — commit a model inference as a first-class, append-only cognition
     * event. Called by the tool pack's {@code commit_inference} client-local tool (via
     * {@link AgentLoopRegistry#commitInference}): appends
     * {@code <inference provenance="inferred">text</inference>} through the normal buffered
     * tail channel — it is seq/schemaVersion/gameTime-stamped by the envelope choke point
     * like every other event, and flushes into the conversation at the next protocol-valid
     * boundary (for a mid-turn tool call: the very next turn). Returns the tool-result
     * confirmation JSON (carrying the assigned seq). Client main thread only, like every
     * loop mutation — the dispatcher invokes local tools there.
     */
    public String commitInference(String text) {
        if (text == null || text.isBlank()) {
            return "{\"success\":false,\"message\":\"empty inference text\"}";
        }
        if (dead) {
            return "{\"success\":false,\"message\":\"body is dead — inference not committed\"}";
        }
        long seq = queueEventNote("<inference provenance=\"inferred\">" + escapeXmlText(text.strip())
                + "</inference>");
        Constants.LOG.info("[numen-entity#{}] inference committed (seq {}): {}",
                entityUuid, seq, truncate(text, 120));
        return "{\"success\":true,\"seq\":" + seq
                + ",\"message\":\"推断已落账,将出现在你的下一轮上下文中\"}";
    }

    /**
     * Wave D landmark-naming seam — the engine half of {@code remember_place}. Called by the pack tool via
     * {@link AgentLoopRegistry#rememberPlace}: names / annotates the landmark at the given coordinate (null
     * {@code x/y/z} → the companion's current block position), reusing the {@link LandmarkStore} +
     * {@link LandmarkEventEmitter} machinery. The resulting {@code added} / {@code renamed} event is drained
     * onto the buffered tail immediately (like {@link #appendWorldCognitionNotes}), so it rides the next turn.
     * Returns the tool-result confirmation JSON; fails soft on a dead body, an unresolved body (no
     * position/dimension), or a blank label. Client main thread only, like every loop mutation.
     */
    public String rememberPlace(Integer x, Integer y, Integer z, String label, String category, String note) {
        if (label == null || label.isBlank()) {
            return "{\"success\":false,\"message\":\"empty label — a place needs a name\"}";
        }
        if (dead) {
            return "{\"success\":false,\"message\":\"body is dead — place not remembered\"}";
        }
        AbstractClientPlayer body = resolveEntity();
        if (body == null) {
            return "{\"success\":false,\"message\":\"body not loaded — cannot resolve position/dimension\"}";
        }
        net.minecraft.core.BlockPos pos = (x != null && y != null && z != null)
                ? new net.minecraft.core.BlockPos(x, y, z)
                : body.blockPosition();
        String dim = body.level().dimension().identifier().toString();
        String cat = (category == null || category.isBlank()) ? null : category.strip();
        String nt = (note == null || note.isBlank()) ? null : note.strip();
        LandmarkStore.NamingResult r = landmarks.rememberPlace(pos, dim, label.strip(), cat, nt);
        landmarkEmitter.emit(landmarks.drainEvents());
        Constants.LOG.info("[numen-entity#{}] remember_place {} \"{}\" at {},{},{} ({})",
                entityUuid, r.id(), label.strip(), pos.getX(), pos.getY(), pos.getZ(),
                r.created() ? "new" : "updated");
        return "{\"success\":true,\"id\":\"" + r.id() + "\",\"created\":" + r.created()
                + ",\"message\":\"" + (r.created() ? "已记住新地点" : "已更新地点")
                + ",之后可用名字或 id 回忆\"}";
    }

    /**
     * Wave D — the engine half of {@code forget_place}. Removes the landmark matching {@code idOrLabel}
     * (an {@code lm_xxx} id or, case-insensitively, its label) and drains the resulting {@code removed}
     * event onto the tail. Fails soft when the body is dead or nothing matched. Client main thread only.
     */
    public String forgetPlace(String idOrLabel) {
        if (idOrLabel == null || idOrLabel.isBlank()) {
            return "{\"success\":false,\"message\":\"empty place id/label\"}";
        }
        if (dead) {
            return "{\"success\":false,\"message\":\"body is dead — nothing forgotten\"}";
        }
        String removed = landmarks.forgetPlace(idOrLabel);
        if (removed == null) {
            return "{\"success\":false,\"message\":\"no landmark matched that id or label\"}";
        }
        landmarkEmitter.emit(landmarks.drainEvents());
        Constants.LOG.info("[numen-entity#{}] forget_place {} (matched {})", entityUuid, removed, idOrLabel.strip());
        return "{\"success\":true,\"id\":\"" + removed + "\",\"message\":\"已忘记该地点\"}";
    }

    /**
     * Wave D — the engine half of {@code recall_places}. Returns the full current landmark list as
     * human-readable text (see {@link LandmarkStore#listPlaces()}), or a short "nothing yet" line.
     * Read-only; no event, no persistence change. Client main thread only.
     */
    public String listPlaces() {
        if (dead) {
            return "(无法回忆:身体已消失)";
        }
        String text = landmarks.listPlaces();
        return text.isEmpty() ? "(暂无已知地标。用 remember_place 给重要地点起名后即可靠名字回忆。)" : text;
    }

    /** Minimal XML text escaping for event bodies we compose from free-form model text. */
    private static String escapeXmlText(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    /**
     * True when a buffered tail entry is an event/note (an XML element open tag: {@code '<'} +
     * letter) rather than a plain owner prompt. Every producer that routes through
     * {@link #queueEventNote} emits such XML (stamped or not); owner prompts are plain text. Used
     * to decide which buffered entries survive a death freeze (carried) versus are discarded.
     */
    private static boolean isEventNote(String s) {
        return s != null && s.length() >= 2 && s.charAt(0) == '<' && Character.isLetter(s.charAt(1));
    }

    // ---- internals ----

    /**
     * Splice any buffered owner prompts into the conversation as a single
     * {@code user} message. Only call this at a protocol-valid point (no
     * assistant reply in flight, no tool results pending) — the callers
     * ({@link #tryStartTurn}) guarantee that. Multiple buffered prompts are
     * joined with newlines into one message to avoid back-to-back {@code user}
     * messages that some backends reject.
     */
    private void flushBufferedPrompts() {
        if (bufferedPrompts.isEmpty()) return;
        String merged = String.join("\n", bufferedPrompts);
        List<String> attachments = List.copyOf(bufferedAttachments);
        bufferedPrompts.clear();
        bufferedAttachments.clear();
        convo.addUser(merged, attachments);
        // A fresh owner directive starts a new tool-chain: restart the turn
        // counter (just log numbering now that the hard cap is gone).
        convo.resetTurnCount();
    }

    private void tryStartTurn() {
        if (dead) {
            Constants.LOG.debug("[numen-entity#{}] tryStartTurn skipped: body dead", entityUuid);
            return;
        }
        if (aborted) {
            Constants.LOG.debug("[numen-entity#{}] tryStartTurn skipped: aborted", entityUuid);
            return;
        }
        if (externallyDriven) {
            Constants.LOG.debug("[numen-entity#{}] tryStartTurn skipped: externally driven", entityUuid);
            return;
        }
        if (awaitingLlmResponse) {
            Constants.LOG.debug("[numen-entity#{}] tryStartTurn skipped: awaitingLlmResponse", entityUuid);
            return;
        }
        if (compacting) {
            Constants.LOG.debug("[numen-entity#{}] tryStartTurn skipped: compacting", entityUuid);
            return;
        }
        if (dispatcher.busy()) {
            Constants.LOG.debug("[numen-entity#{}] tryStartTurn skipped: tool call(s) outstanding", entityUuid);
            return;
        }
        // Safe point: no assistant reply in flight and no tool results
        // outstanding, so the conversation ends with either a tool result or a
        // final assistant message — a user message can now be appended legally.
        // ICE: this is also THE turn boundary at which a completed async (long-track)
        // recast is spliced in — retire the oldest ~90% and reset the token gate BEFORE
        // this turn is composed, so the gates below see the reduced context.
        if (readyAsyncSummary != null) {
            applyReadyCompaction();
        }
        // Fold in world-cognition tail notes (first-session snapshot / landmark
        // events / date rollover) only when a turn is actually going to run.
        if (!bufferedPrompts.isEmpty() || !convo.snapshot().isEmpty()) {
            appendWorldCognitionNotes();
        }
        flushBufferedPrompts();
        if (convo.snapshot().isEmpty()) return;
        // No hard cap on tool-call turns and no loop guard — a capable agent
        // legitimately chains many tasks, and resuming a timed-out move_to
        // repeats the exact same call. Runaways are stopped by the owner's
        // interrupt.
        if (!NumenLlmClient.isConfigured()) {
            Constants.LOG.warn("[numen-entity#{}] API key not set; open the Numen GUI (X) → Settings",
                    entityUuid);
            aborted = true;
            return;
        }

        int window = com.dwinovo.numen.agent.model.ModelRegistry.contextWindow(
                com.dwinovo.numen.client.screen.LlmProviders.normalize(com.dwinovo.numen.platform.Services.CONFIG.getProvider()),
                com.dwinovo.numen.platform.Services.CONFIG.getModel());

        // HARD gate (window − 13k): the last request's true context size is within the buffer
        // of the window — the recast can no longer be deferred. Mirrors Claude Code's
        // autoCompactIfNeeded, extended for ICE:
        //   • an async recast is already in flight → FREEZE and WAIT for it (don't dispatch a
        //     second); it splices + resumes this turn when it lands (finishAsyncCompaction);
        //   • none in flight (async disabled or failed) → the legacy BLOCKING path, unchanged.
        if (lastPromptTokens >= window - AUTO_COMPACT_BUFFER_TOKENS
                && convo.snapshot().size() >= MIN_COMPACT_MESSAGES
                && compactFailures < MAX_COMPACT_FAILURES) {
            if (asyncCompacting) {
                resumeTurnAfterAsync = true;
                Constants.LOG.info("[numen-entity#{}] hard limit ({} tokens >= {} - {}) with async recast in flight — freezing to wait",
                        entityUuid, lastPromptTokens, window, AUTO_COMPACT_BUFFER_TOKENS);
                return;
            }
            Constants.LOG.info("[numen-entity#{}] auto-compacting (blocking): last prompt {} tokens >= {} - {}",
                    entityUuid, lastPromptTokens, window, AUTO_COMPACT_BUFFER_TOKENS);
            startCompaction(true);
            return;
        }

        // SOFT gate (0.7 × window): ICE early trigger — dispatch a background recast and
        // KEEP RUNNING on the full context. Non-blocking, guarded to one in flight.
        maybeStartAsyncCompaction(window);

        // This turn runs at emergency effort iff it was started by an urgent event
        // (consume the flag — any continuation turns after it are ordinary/base effort).
        boolean emergency = pendingUrgent;
        pendingUrgent = false;
        dispatchLlmTurn(emergency);
    }

    // ---- ICE Phase 1: async (long-track) compaction — trigger / cut / dispatch / splice ----

    /**
     * ICE soft trigger. When enabled and the last request crossed the soft water line
     * ({@link #SOFT_COMPACT_FRACTION} × window), dispatch ONE background compaction of the
     * retired region and keep running. Guarded so it fires at most once per crossing:
     * skipped while a recast is in flight, while a blocking compaction runs, while a summary
     * is already staged, and once the circuit breaker has tripped. Only reached below the hard
     * gate, so it never races the blocking path.
     */
    private void maybeStartAsyncCompaction(int window) {
        if (!Services.CONFIG.isAsyncCompaction()) return;
        if (asyncCompacting || compacting || readyAsyncSummary != null) return;
        if (compactFailures >= MAX_COMPACT_FAILURES) return;
        if (lastPromptTokens < (int) (window * SOFT_COMPACT_FRACTION)) return;
        if (convo.snapshot().size() < MIN_COMPACT_MESSAGES) return;
        startAsyncCompaction();
    }

    /**
     * Dispatch the background summarization of the retired region {@code [0..cutIndex)} — the
     * ICE "long-track" recast (§4.3, §7). Reuses the blocking compaction plumbing (minimal
     * system prompt, no tools) but WITHOUT freezing the loop and WITHOUT touching turn
     * generations: the live loop continues on the FULL context; the recast is orthogonal to
     * turns, and its staleness is caught by the {@link ConvoState#structuralEpoch() structural
     * epoch}, not the turn generation.
     *
     * <p>The retired region is context the model already processed live this session, so the
     * summarization re-reads the already-warm prefix — under prefix caching its input bills
     * mostly at cache-read price, a hidden win (ICE §6.1 cache economics). {@code companion=null}
     * keeps the context exporter's wire capture pinned to the LIVE conversation, not this
     * background summarizer.
     */
    private void startAsyncCompaction() {
        List<ConvoState.Msg> full = convo.snapshot();
        int cutIndex = protocolSafeCutIndex(full, COMPACT_CUT_FRACTION);
        if (cutIndex < MIN_COMPACT_MESSAGES) {
            // No protocol-safe boundary retires enough yet (e.g. one long open tool chain).
            // Not an error — retry at the next soft crossing.
            Constants.LOG.debug("[numen-entity#{}] async recast skipped: no protocol-safe cut >= {} in {} msgs",
                    entityUuid, MIN_COMPACT_MESSAGES, full.size());
            return;
        }
        asyncCompacting = true;
        asyncCutIndex = cutIndex;
        asyncEpoch = convo.structuralEpoch();

        List<ConvoState.Msg> request = new ArrayList<>(full.subList(0, cutIndex));
        request.add(new ConvoState.Msg.User(COMPACT_PROMPT));
        Constants.LOG.info("[numen-llm] async compaction dispatched (ICE long-track recast): retiring {} of {} msgs "
                        + "at protocol-safe cut (epoch {}) — re-reads warm prefix, loop continues",
                cutIndex, full.size(), asyncEpoch);
        NumenLlmClient.instance()
                .chatStreaming(null, request, List.of(), COMPACT_SYSTEM_PROMPT, null, null, null)
                .whenComplete((res, err) -> Minecraft.getInstance().execute(
                        () -> finishAsyncCompaction(res, err)));
    }

    /**
     * The newest PROTOCOL-SAFE cut boundary retiring roughly the first {@code fraction} of
     * {@code msgs}: the largest index {@code c ≤ floor(fraction · n)} such that
     * <ul>
     *   <li>{@code messages[0..c)} is self-contained — every {@code assistant(tool_calls)} in
     *       it has all its matching {@code tool} results in it (no open call straddles the
     *       cut), so retiring it leaves no orphan tool result at the head of the tail; and</li>
     *   <li>{@code messages[c]} (the tail's first message) is an {@code assistant} turn — so
     *       the spliced history {@code [summary_user, assistant, …]} stays protocol-valid and
     *       never produces back-to-back {@code user} messages that some backends reject.</li>
     * </ul>
     * Returns {@code 0} when no such boundary exists (caller then skips the recast).
     */
    private static int protocolSafeCutIndex(List<ConvoState.Msg> msgs, double fraction) {
        int n = msgs.size();
        int target = (int) Math.floor(n * fraction);
        int pending = 0;   // open (unanswered) tool_call count across messages[0..c)
        int best = 0;
        for (int c = 1; c <= target; c++) {
            ConvoState.Msg prev = msgs.get(c - 1);
            if (prev instanceof ConvoState.Msg.Assistant a) {
                pending += a.turn().toolCalls().size();
            } else if (prev instanceof ConvoState.Msg.Tool && pending > 0) {
                pending--;
            }
            if (pending == 0 && msgs.get(c) instanceof ConvoState.Msg.Assistant) {
                best = c;
            }
        }
        return best;
    }

    /**
     * The background recast landed (main thread). Validate, then move to the READY state and
     * either splice now or defer to the next turn boundary:
     * <ul>
     *   <li><b>frozen (dead)</b> → drop the result (the loop restores its own context on
     *       respawn);</li>
     *   <li><b>transport error / empty summary</b> → clear the in-flight flag, log, do NOT
     *       trip the circuit breaker (that guards the blocking safety net), retry at the next
     *       soft crossing. If we were frozen at the hard limit, fall back to blocking now;</li>
     *   <li><b>stale</b> (a blocking compaction or a death replaceAll bumped the structural
     *       epoch) → DISCARD the summary (ICE §4.5: never splice over a history that moved);</li>
     *   <li><b>valid</b> → stage it; splice immediately if the loop is idle, resume a frozen
     *       turn if one was waiting, else leave it for {@link #tryStartTurn} to apply.</li>
     * </ul>
     */
    private void finishAsyncCompaction(NumenLlmClient.ChatResult res, Throwable err) {
        recordUsage(res);   // background summarization is billed like any other call — count it
        asyncCompacting = false;
        int cutIndex = asyncCutIndex;
        long epoch = asyncEpoch;
        asyncCutIndex = -1;
        asyncEpoch = -1;
        boolean resume = resumeTurnAfterAsync;
        resumeTurnAfterAsync = false;

        if (dead) {
            Constants.LOG.info("[numen-entity#{}] async recast result dropped — body frozen (dead)", entityUuid);
            return;
        }

        String summary = (err == null && res != null && res.turn() != null) ? res.turn().content() : null;
        if (summary == null || summary.isBlank()) {
            Constants.LOG.warn("[numen-entity#{}] async recast failed: {} — retry at next soft threshold",
                    entityUuid, err != null ? unwrap(err) : "empty summary");
            if (resume) tryStartTurn();   // frozen at hard limit → fall back to the blocking path now
            return;
        }

        if (convo.structuralEpoch() != epoch) {
            Constants.LOG.info("[numen-entity#{}] discarding stale async recast (epoch {} != {}) — a blocking compaction or death intervened",
                    entityUuid, epoch, convo.structuralEpoch());
            if (resume) tryStartTurn();   // context already reduced by the intervening path; just resume
            return;
        }

        // Valid — stage it for the splice.
        readyAsyncSummary = summary;
        readyAsyncCutIndex = cutIndex;
        readyAsyncEpoch = epoch;

        if (resume) {
            // A turn froze at the hard limit waiting for this: splice + resume it now.
            tryStartTurn();
        } else if (!awaitingLlmResponse && !dispatcher.busy() && !compacting) {
            // Idle at a turn boundary: retire the region now (cut the token cost immediately),
            // but do NOT start an unprompted turn — the loop only dispatches on real triggers.
            applyReadyCompaction();
        }
        // else: a turn is in flight — leave it staged; tryStartTurn splices at the next boundary.
    }

    /**
     * Splice a staged async summary in at the current (turn-boundary) point: retire
     * {@code messages[0..cutIndex)} into {@code [summary]} and keep the tail — the kept messages
     * PLUS everything appended while the recast was in flight — byte-identical after it. Mirrors
     * the tail of {@link #finishCompaction}: re-lay the world-cognition baseline into the summary
     * message, persist the boundary (a {@code compact} divider + a fresh copy of the surviving
     * tail so a relaunch replays {@code [summary] + tail}), renumber (clear per-turn reasoning),
     * and reset the token gate. Re-checks the structural epoch first and discards if it moved.
     */
    private void applyReadyCompaction() {
        String summary = readyAsyncSummary;
        int cutIndex = readyAsyncCutIndex;
        long epoch = readyAsyncEpoch;
        clearReadyCompaction();

        if (convo.structuralEpoch() != epoch) {
            Constants.LOG.info("[numen-entity#{}] async recast not spliced (epoch {} != {}) — history moved before the boundary",
                    entityUuid, epoch, convo.structuralEpoch());
            return;
        }
        List<ConvoState.Msg> before = convo.snapshot();
        if (cutIndex <= 0 || cutIndex > before.size()) {
            Constants.LOG.warn("[numen-entity#{}] async recast cutIndex {} invalid for {} msgs — discarding",
                    entityUuid, cutIndex, before.size());
            return;
        }

        String wrapped = SUMMARY_HEADER + summary.strip();
        // Re-lay the world-cognition baseline into the SAME summary user message (a lone
        // snapshot after it would be back-to-back user) — the model regains full landmark
        // state; diffs resume from here. Exactly as finishCompaction does.
        wrapped = wrapped + "\n\n" + stampIfEvent(buildContextSnapshotNote(resolveEntity()));
        lastKnownDate = LocalDate.now();

        List<ConvoState.Msg> tail = new ArrayList<>(before.subList(cutIndex, before.size()));
        // Persist the boundary first (relaunch replays [summary] + tail), then swap in memory.
        log.appendCompactPrefixBoundary(wrapped, tail);
        convo.replacePrefix(cutIndex, new ConvoState.Msg.User(wrapped));
        turnReasoning.clear();   // snapshot indices renumbered by the recast — stored reasoning no longer maps
        lastPromptTokens = 0;    // unknown until the next request reports usage
        compactFailures = 0;     // a clean recast proves the path works
        Constants.LOG.info("[numen-llm] async compaction spliced: {} msgs -> summary + {} tail msgs",
                cutIndex, tail.size());
    }

    /** Clear the staged async summary. */
    private void clearReadyCompaction() {
        readyAsyncSummary = null;
        readyAsyncCutIndex = -1;
        readyAsyncEpoch = -1;
    }

    /**
     * Fire the streaming LLM call for one turn on the current conversation. Shared by the
     * normal path ({@link #tryStartTurn}) and the emergency preemption path
     * ({@link #preemptWithEmergencyTurn}). An {@code emergency} turn runs at the configured
     * {@code emergencyEffort} (default {@code "off"}) so a reaction lands fast; a normal turn
     * passes no override and falls back to the client's configured base effort. The captured
     * generation stamps the response: if the turn is superseded (owner interrupt, death, or a
     * newer emergency preemption) before it lands, {@link #handleResponse} discards it, and the
     * gated {@code onReasoning} stops the stale stream from writing the live display.
     */
    private void dispatchLlmTurn(boolean emergency) {
        convo.incrementTurn();
        awaitingLlmResponse = true;
        // Refractory latch: while an EMERGENCY turn's response is awaited, urgent events
        // buffer instead of preempting (see injectEvent). Mirrors awaitingLlmResponse.
        awaitingEmergency = emergency;

        var tools = ToolRegistry.all();
        var snapshot = convo.snapshot();
        INumenConfig config = Services.CONFIG;
        String systemPrompt = composeSystemPrompt(config.getSystemPrompt());
        // Emergency → emergencyEffort override (e.g. "off"); normal → null (use base config).
        String effortOverride = emergency ? config.getEmergencyEffort() : null;

        Constants.LOG.info("[numen-entity#{}] turn {}: convo={} msgs, tools={}, effort={}",
                entityUuid, convo.turnCount(), snapshot.size(), tools.size(),
                emergency ? "EMERGENCY(" + effortOverride + ")" : "base");

        // Capture the current generation; if the turn is superseded before this call
        // resolves, handleResponse sees the mismatch and discards it. The onReasoning
        // callback is gated on the same generation so a superseded stream stops writing
        // the shared live-reasoning display (the active turn owns it).
        final int gen = turnGeneration;
        NumenLlmClient.instance().chatStreaming(entityUuid, snapshot, tools, systemPrompt, effortOverride, null,
                        s -> { if (gen == turnGeneration) liveReasoning = s; })
                .whenComplete((res, err) -> bounceBackToMain(gen, res, err));
    }

    /**
     * An urgent event arrived while a slow think was streaming — supersede it with a fast
     * emergency turn. The in-flight response has appended nothing yet, so the conversation is
     * a protocol-valid base for a new turn. We bump {@link #turnGeneration} (the superseded
     * response is discarded — and harvested — when it lands), flush the urgent event in as the
     * new user message, and dispatch an emergency-effort turn in its place.
     *
     * <p>The superseded stream keeps running to completion (the JDK HTTP client offers no clean
     * mid-stream cancel), but it is inert: its response fails the generation check so it neither
     * executes tools nor appends an assistant message, and its {@code onReasoning} is gated off.
     * When it lands we harvest its thinking into a note for the next turn (see
     * {@link #harvestPreemptedTurn}).
     */
    private void preemptWithEmergencyTurn() {
        lastPreemptTick = clientTicks;       // start the preempt cooldown window (one per 60 ticks)
        int supersededGen = turnGeneration;
        preemptedGenerations.add(supersededGen);
        turnGeneration++;                    // in-flight response now stale → discarded + harvested on arrival
        pendingUrgent = false;               // consumed: this emergency turn IS the urgent reaction
        liveReasoning = "";                  // drop the superseded think's live display; the emergency turn owns it now
        // Keep the convo protocol-valid: if the superseded turn's trigger is still the trailing
        // user message, appending the urgent event would create back-to-back user messages (some
        // backends reject those). Cap it with the same marker the owner-interrupt path uses.
        if (convo.lastMessage() instanceof ConvoState.Msg.User) {
            convo.addAssistant(new AssistantTurn("(被紧急事件打断)", List.of(), null));
        }
        Constants.LOG.info("[numen-entity#{}] URGENT preempt: superseding in-flight think (gen {} → {})",
                entityUuid, supersededGen, turnGeneration);
        flushBufferedPrompts();              // urgent event (+ any queued) → one user message
        dispatchLlmTurn(true);               // emergency effort, on the bumped generation
    }

    /**
     * Fold a superseded (emergency-preempted) turn's thinking into a note for the next turn, so
     * the newest reasoning the model produced before the interruption isn't simply lost. Rides
     * the ordinary buffered-prompt path (non-urgent) as an appended user-role
     * {@code <system-reminder>} — constitution-conform tail append, never a mid-conversation
     * system message. The reasoning tail is capped to {@link #HARVEST_REASONING_CAP} chars,
     * truncated from the FRONT so the newest thinking is kept. No-op when there is nothing to keep.
     */
    private void harvestPreemptedTurn(AssistantTurn turn) {
        String reasoning = extractReasoning(turn.extras());
        String finalText = turn.content() == null ? "" : turn.content();
        if (reasoning.isBlank() && finalText.isBlank()) {
            Constants.LOG.info("[numen-entity#{}] preempted turn had nothing to harvest", entityUuid);
            return;
        }
        String tail = frontTruncate(reasoning, HARVEST_REASONING_CAP);
        StringBuilder note = new StringBuilder(
                "<system-reminder>你上一轮被紧急事件打断,当时未执行的思考如下(仅供参考,其动作未生效):");
        if (!tail.isBlank()) note.append('\n').append(tail);
        if (!finalText.isBlank()) note.append('\n').append(finalText);
        note.append("</system-reminder>");
        queueEventNote(note.toString());
        Constants.LOG.info("[numen-entity#{}] harvested preempted think ({} reasoning chars, {} text chars) → next turn",
                entityUuid, reasoning.length(), finalText.length());
    }

    /** Pull the reasoning transcript out of an assistant turn's round-tripped extras ({@code ""} if none). */
    private static String extractReasoning(com.google.gson.JsonObject extras) {
        if (extras == null) return "";
        for (String key : REASONING_EXTRA_KEYS) {
            var el = extras.get(key);
            if (el != null && el.isJsonPrimitive()) return el.getAsString();
        }
        return "";
    }

    /** Keep the last {@code maxChars} characters (newest reasoning), marking a front elision with an ellipsis. */
    private static String frontTruncate(String s, int maxChars) {
        if (s == null) return "";
        if (s.length() <= maxChars) return s;
        return "…" + s.substring(s.length() - maxChars);
    }

    // ---- compaction ----

    /**
     * Owner pressed the GUI's Compact button. Runs the same machinery as the
     * automatic path; silently ignored when a turn is in flight or there is
     * too little history to be worth a summarization call.
     */
    public void requestCompact() {
        if (!canCompact()) {
            Constants.LOG.info("[numen-entity#{}] manual compact ignored (busy={}, msgs={})",
                    entityUuid, isBusy(), convo.snapshot().size());
            return;
        }
        if (!NumenLlmClient.isConfigured()) return;
        startCompaction(false);
    }

    /**
     * Fire the summarization call: full history + the compact prompt as the
     * final user message, NO tools, a minimal system prompt (skills XML and the
     * persona would only waste the very tokens we're trying to reclaim).
     */
    private void startCompaction(boolean auto) {
        compacting = true;
        List<ConvoState.Msg> request = new ArrayList<>(convo.snapshot());
        request.add(new ConvoState.Msg.User(COMPACT_PROMPT));
        Constants.LOG.info("[numen-entity#{}] compaction started ({}, {} msgs)",
                entityUuid, auto ? "auto" : "manual", request.size() - 1);
        final int gen = turnGeneration;
        // No per-call effort override (null) → summarization uses the configured base effort.
        NumenLlmClient.instance().chatStreaming(entityUuid, request, List.of(), COMPACT_SYSTEM_PROMPT, null, null, null)
                .whenComplete((res, err) -> Minecraft.getInstance().execute(
                        () -> finishCompaction(gen, auto, res, err)));
    }

    private void finishCompaction(int gen, boolean auto,
                                  NumenLlmClient.ChatResult res, Throwable err) {
        recordUsage(res);   // summarization calls bill like any other — count them too
        if (gen != turnGeneration) {
            Constants.LOG.info("[numen-entity#{}] discarding interrupted compaction (gen {} != {})",
                    entityUuid, gen, turnGeneration);
            return;   // abort() already reset the compacting flag
        }
        compacting = false;

        String summary = (err == null && res != null) ? res.turn().content() : null;
        if (summary == null || summary.isBlank()) {
            compactFailures++;
            Constants.LOG.warn("[numen-entity#{}] compaction failed ({}/{}): {}",
                    entityUuid, compactFailures, MAX_COMPACT_FAILURES,
                    err != null ? unwrap(err) : "empty summary");
            // The conversation is untouched — the next turn just runs uncompacted.
            if (auto || !bufferedPrompts.isEmpty()) tryStartTurn();
            return;
        }

        String wrapped = SUMMARY_HEADER + summary.strip();
        // Right after compaction, re-lay the world-cognition baseline so the model regains
        // full landmark state (diffs resume from here). Folded into the SAME summary user
        // message rather than as a separate note — a lone snapshot after the summary user
        // message would be back-to-back user (some backends reject that). Constitution-safe:
        // still user-role tail content, and persisted together with the summary.
        wrapped = wrapped + "\n\n" + stampIfEvent(buildContextSnapshotNote(resolveEntity()));
        lastKnownDate = LocalDate.now();
        // Boundary into the JSONL first (relaunches replay the compacted view;
        // the raw pre-compaction history stays in the file as an archive), then
        // swap the in-memory history without re-notifying the sink.
        log.appendCompactSummary(wrapped);
        convo.replaceAll(List.of(new ConvoState.Msg.User(wrapped)));
        turnReasoning.clear();  // snapshot indices renumbered — stored reasoning no longer maps
        lastPromptTokens = 0;   // unknown until the next request reports usage
        compactFailures = 0;
        Constants.LOG.info("[numen-entity#{}] compaction done ({}): history → 1 summary msg ({} chars)",
                entityUuid, auto ? "auto" : "manual", wrapped.length());

        // Auto-compaction interrupted a turn that was about to dispatch —
        // resume it so the task chain continues on the compacted history. After
        // a MANUAL compact we stay idle unless prompts queued up meanwhile.
        if (auto || !bufferedPrompts.isEmpty()) tryStartTurn();
    }

    /**
     * Build the <strong>byte-frozen</strong> system prefix: base config prompt + the static
     * {@code ENTITY_PROMPT} + a session-constant {@code <env>} (owner name + entity uuid) +
     * the static world-cognition protocol + the (stable) skills XML. Everything here is
     * constant for the session, so the prompt cache prefix never moves. Volatile world state
     * — dimension, today's date, the landmark list — is deliberately NOT here; it arrives as
     * append-only tail notes (context snapshot, {@code <landmark_event>}, date rollover).
     */
    private String composeSystemPrompt(String basePrompt) {
        String base = basePrompt == null ? "" : basePrompt;
        String skillsXml = SkillRegistry.instance().formatXml();

        StringBuilder sb = new StringBuilder();
        if (!base.isBlank()) sb.append(base);
        sb.append(ENTITY_PROMPT);
        sb.append("\n\n").append(buildStaticEnvBlock());
        sb.append("\n\n").append(com.dwinovo.numen.agent.prompt.NumenPrompts.WORLD_COGNITION_PROTOCOL);
        if (!skillsXml.isEmpty()) {
            sb.append("\n\n").append(skillsXml);
        }
        return sb.toString();
    }

    /**
     * The session-constant {@code <env>}: only fields that never change for this loop's
     * lifetime — the companion's uuid and its owner's name (the brain runs on the owner's
     * client, so the local player IS the owner). No dimension, no date (those jitter the
     * prefix and now ride the tail instead).
     */
    private String buildStaticEnvBlock() {
        var localOwner = Minecraft.getInstance().player;
        String ownerName = localOwner != null ? localOwner.getName().getString() : "unknown";
        return "<env>\n"
                + "  entity_uuid: " + entityUuid + "\n"
                + "  owner_name: " + ownerName + "\n"
                + "</env>";
    }

    /**
     * Append world-cognition tail notes at a turn boundary (called just before the buffered
     * prompts flush, so they merge into the same user-role message):
     * <ul>
     *   <li>first turn of a session → a full {@code context_snapshot} note (baseline);</li>
     *   <li>otherwise → self-heal the landmark store and emit any queued
     *       {@code <landmark_event>}s (added during the prior turn's harvest, removed by
     *       verification now);</li>
     *   <li>a mid-session date rollover → a small "今天是…" note.</li>
     * </ul>
     */
    private void appendWorldCognitionNotes() {
        AbstractClientPlayer body = resolveEntity();
        if (contextSnapshotPending) {
            // Context first — stamped like any event, then placed at the head of the flush.
            bufferedPrompts.add(0, stampIfEvent(buildContextSnapshotNote(body)));
            contextSnapshotPending = false;
            lastKnownDate = LocalDate.now();
            return;   // first turn: the snapshot IS the baseline; diffs resume next turn
        }
        // Ongoing: verify against the live world (queues removed events), then emit
        // everything accumulated since the last boundary (added + removed) as tail notes.
        if (body != null) {
            landmarks.verify(body.level(), body.level().dimension().identifier().toString());
        }
        landmarkEmitter.emit(landmarks.drainEvents());
        // Date rollover — cheap once-per-turn check.
        LocalDate today = LocalDate.now();
        if (lastKnownDate != null && !today.equals(lastKnownDate)) {
            queueEventNote("<system-reminder>今天是" + today + "。</system-reminder>");
            lastKnownDate = today;
        }
    }

    /**
     * Build the one-shot {@code context_snapshot} tail note: today's date + current dimension
     * + the full landmark baseline (grouped by dimension). Self-heals first so the baseline is
     * accurate, then discards the verify-produced events (they're folded into the snapshot,
     * not emitted separately). Used on the first session turn and, inline, right after a compaction.
     */
    private String buildContextSnapshotNote(AbstractClientPlayer body) {
        String dim = body != null ? body.level().dimension().identifier().toString() : "unknown";
        if (body != null) {
            landmarks.verify(body.level(), dim);
            landmarks.drainEvents();   // baseline: reflected in the snapshot, not emitted twice
        }
        String landmarkBody = landmarks.renderSnapshotBody();
        StringBuilder sb = new StringBuilder("<system-reminder kind=\"context_snapshot\">今天是");
        sb.append(LocalDate.now()).append("。当前维度:").append(dim).append("。");
        sb.append(landmarkBody.isEmpty() ? "(暂无已知地标)" : "\n" + landmarkBody);
        sb.append("</system-reminder>");
        return sb.toString();
    }

    private AbstractClientPlayer resolveEntity() {
        return ClientNumenLookup.resolve(entityUuid);
    }

    private void bounceBackToMain(int gen, NumenLlmClient.ChatResult res, Throwable err) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> handleResponse(gen, res, err));
    }

    private void handleResponse(int gen, NumenLlmClient.ChatResult res, Throwable err) {
        // Usage accounting first, BEFORE the generation check: a discarded (interrupted)
        // response was still billed by the backend, so it still counts.
        recordUsage(res);
        // Owner interrupted this turn while the call was in flight: abort()
        // already settled the conversation (and, if a newer turn has since
        // started, awaitingLlmResponse belongs to *that* call). Discard wholesale
        // — do NOT touch awaitingLlmResponse here, or we'd clear the newer turn's.
        if (gen != turnGeneration) {
            Constants.LOG.info("[numen-entity#{}] discarding superseded LLM response (gen {} != {})",
                    entityUuid, gen, turnGeneration);
            // If this was superseded by an emergency preemption (not an owner/death interrupt),
            // harvest its thinking for the next turn. Do NOT touch liveReasoning: it now belongs
            // to the active turn (a later emergency turn may already be streaming into it).
            if (preemptedGenerations.remove(gen) && res != null && res.turn() != null) {
                harvestPreemptedTurn(res.turn());
            }
            return;
        }
        awaitingLlmResponse = false;
        awaitingEmergency = false;   // refractory ends with the awaited response — success OR failure below
        // The turn's response has landed — its thinking is done streaming. Capture the
        // live transcript (moved to per-turn storage below, once the assistant message
        // is appended and its index is known) and clear the live channel.
        String finishedReasoning = liveReasoning;
        liveReasoning = "";

        // World is unloading (owner quit / disconnected): the client→server channel is gone, so a
        // dispatched ExecuteToolPayload would NPE in the platform sender. Drop this turn quietly.
        if (Minecraft.getInstance().getConnection() == null) {
            Constants.LOG.info("[numen-entity#{}] client disconnected — dropping LLM turn", entityUuid);
            aborted = true;
            return;
        }

        if (err != null) {
            Constants.LOG.warn("[numen-entity#{}] LLM call failed: {}",
                    entityUuid, unwrap(err));
            aborted = true;
            return;
        }
        if (res == null || res.turn() == null) {
            Constants.LOG.warn("[numen-entity#{}] LLM returned null turn", entityUuid);
            aborted = true;
            return;
        }
        AssistantTurn turn = res.turn();
        // True context size of the request we just made — the auto-compaction
        // signal. 0 when the backend sent no usage frame (then auto never fires).
        if (res.promptTokens() > 0) {
            lastPromptTokens = res.promptTokens();
        }

        convo.addAssistant(turn);
        // Keep the finished turn's reasoning browsable in the panel, keyed by the index
        // the assistant message just received (append-only until compaction clears the map).
        if (!finishedReasoning.isBlank()) {
            turnReasoning.put(convo.snapshot().size() - 1, finishedReasoning);
        }

        if (!turn.hasToolCalls()) {
            // Final text reply — spoken to the owner. Chain settles; the next
            // prompt resumes the same conversation with a fresh turn count.
            if (!turn.content().isEmpty()) {
                Constants.LOG.info("[numen-entity#{}] assistant (final): {}",
                        entityUuid, turn.content());
            } else {
                Constants.LOG.info("[numen-entity#{}] assistant (final, empty content)", entityUuid);
            }
            convo.resetTurnCount();
            // A prompt that arrived during this final turn was buffered; now that
            // the chain has settled, start a fresh turn to answer it.
            if (!bufferedPrompts.isEmpty()) tryStartTurn();
            return;
        }

        // Hand this turn's calls to the dispatcher — it runs them serially and
        // reports each result back through the sink (into the conversation), then
        // calls onAllSettled so the loop starts the next turn.
        dispatcher.dispatch(turn.toolCalls().stream()
                .map(tc -> new ToolInvocation(tc.id(), tc.name(), tc.arguments()))
                .toList());
    }

    /** Feed a landed response's usage frame into the session {@link UsageTracker}. */
    private void recordUsage(NumenLlmClient.ChatResult res) {
        if (res == null) return;
        UsageTracker.instance().record(entityUuid, res.promptTokens(),
                Math.max(0, res.totalTokens() - res.promptTokens()), res.cachedTokens());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String unwrap(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur != cur.getCause()) cur = cur.getCause();
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }
}
