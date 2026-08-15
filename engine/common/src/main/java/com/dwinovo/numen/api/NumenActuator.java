package com.dwinovo.numen.api;

import com.dwinovo.numen.agent.tool.ClientToolContext;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.ClientControl;
import com.dwinovo.numen.client.agent.ClientDeaths;
import com.dwinovo.numen.client.agent.ClientNumenLookup;
import com.dwinovo.numen.client.agent.NumenRoster;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The public entry point for driving a companion's <strong>body</strong> from
 * an outside brain — an MCP server, a remote agent, anything that wants to be
 * the one making the gameplay decisions instead of the companion's built-in LLM.
 *
 * <h2>Actuator — the outside-brain door</h2>
 * {@code NumenActuator} lets an <em>external</em> brain skip the built-in LLM
 * entirely: it lists tools, takes control of a body, calls tools directly, and
 * reads their results. The built-in brain steps aside. (Contrast the built-in
 * brain's own path, which reasons over the companion's conversation and decides
 * for itself — that stays in charge for chat / bridges.)
 *
 * <h2>W9 — server-backed lease client, per-session identity</h2>
 * Control is server-authoritative ({@code com.dwinovo.numen.entity.ControlRegistry}); this
 * class is a thin client for it, never a second source of truth. {@link #acquire} and {@link
 * #release} are real round trips — a {@link ControlRequestPayload} out, the answering {@link
 * com.dwinovo.numen.network.payload.ControlStatePayload} back in (via {@link ClientControl}) —
 * not a local boolean flip. {@link #invoke} carries the {@code sessionToken} {@link #acquire}
 * handed back and is rejected, in-process, for any caller that is not the CURRENT holder: two
 * competing external brains live in this same JVM (the MCP bridge is hosted inside the owner's
 * client), so this in-process check is what actually stops them racing each other, with the
 * server-side gate ({@code ControlRegistry.authorize}, W4) as the backstop against a stale
 * client. Acquiring a body no longer mints an {@code EntityAgentLoop} or replays its conversation
 * from disk — that was the exact opposite of standing the built-in brain down, and this class now
 * touches {@link AgentLoopRegistry} only via its read-only {@link AgentLoopRegistry#get} (to stop
 * a stray in-flight built-in turn on a successful acquire) and {@link
 * AgentLoopRegistry#harvestExternal} (landmark memory — see {@link #invoke}'s doc).
 *
 * <h2>The contract: acquire → invoke* (→ heartbeat) → release</h2>
 * Before driving a companion, {@link #acquire} it — this is a real request to the server; it
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
 * #acquire} several companions and {@link #invoke} on each — they execute
 * concurrently. Mixed fleets work too: acquire A and B, leave C to its built-in
 * brain (or to a DIFFERENT external brain — {@link #companions} reports who
 * currently holds what, so a fleet of competing brains can triage without a
 * round trip per body).
 *
 * <h2>No conversation side effects</h2>
 * A headless {@link #invoke} does NOT touch the companion's conversation log or
 * its built-in brain's memory. The result is returned to the caller only; the
 * external brain owns the context. (The built-in brain, being paused, records
 * nothing — but world events that happen during takeover still accrue into its
 * context tail append-only, so it resumes with an accurate picture on release.)
 *
 * <h2>Client-side API, any thread</h2>
 * Companions are driven by their owner's game client, so this must be called in
 * the owner's client process. Every method is safe to call from any thread — the
 * work is marshalled onto the client main thread and a {@link CompletableFuture}
 * carries the result back.
 */
public final class NumenActuator {

    /** Synthetic tool-call ids for headless invocations, disjoint from the LLM's ids. */
    private static final AtomicLong SEQ = new AtomicLong();

    /** How long {@link #acquire}/{@link #release} wait for the server's answering control-state
     *  push before giving up — see {@link ClientControl#awaitAcquire}. */
    private static final long ACQUIRE_RELEASE_TIMEOUT_MILLIS = 10_000L;

    /**
     * Outstanding {@link #invoke} calls per companion — just enough bookkeeping for {@link
     * #onLeaseLost} to fail them immediately when the lease disappears mid-call (owner
     * force-release, expiry, dismissal), instead of leaving the external caller waiting on a call
     * whose body it no longer holds. Mirrors the design doc's handover-hook step 3 ("complete
     * every in-flight MCP future... with an explanatory error result, not a bare timeout") for
     * the headless path specifically — the server-side task cancellation already stops the BODY;
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

    /** One of the owner's live companions, enriched with control + life state so an external
     *  brain can triage its fleet and see contention without a round trip per body. {@code
     *  state}/{@code controllerLabel} come from {@link ClientControl} (empty label unless {@code
     *  state == EXTERNAL}); {@code alive}/{@code respawnRemainingMs} from {@code ClientDeaths}. */
    public record Companion(UUID uuid, String name, ControlState state, String controllerLabel,
                             boolean alive, int respawnRemainingMs) {}

    /** Outcome of {@link #acquire}: {@code ok} whether the lease was granted; {@code
     *  sessionToken} the lease token to pass to every subsequent {@link #invoke}/{@link
     *  #heartbeat}/{@link #release} call (empty when refused); {@code reason} a human-readable
     *  explanation — who holds it, or why the round trip failed — always empty when {@code ok}. */
    public record AcquireResult(boolean ok, String sessionToken, String reason) {}

    /**
     * The owner's companions currently live in the world — what an external brain
     * picks from. Names are what the owner sees; the UUID is the handle for every
     * other method here.
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
                boolean dead = ClientDeaths.isDead(id);
                int respawnMs = dead ? (int) Math.min(Integer.MAX_VALUE, ClientDeaths.remainingMs(id)) : 0;
                out.add(new Companion(id, e.name(), state, label, !dead, respawnMs));
            }
            f.complete(out);
        });
        return f;
    }

    /**
     * Take external control of {@code companion} — a real server round trip ({@link
     * ControlRequestPayload}{@code op=ACQUIRE} out, the answering control-state push back in via
     * {@link ClientControl#awaitAcquire}), NOT a local flag flip. Refused (never stolen) when
     * another lease is already live for this body; {@link AcquireResult#reason()} names the
     * current holder. Not idempotent across different controllers: calling this twice for the
     * SAME {@code controllerId}/{@code label} while already holding the lease would still just
     * re-run the round trip and get told "another brain holds it" — namely, this one's own prior
     * acquire — since {@code ControlRegistry.acquire} is a plain compare-and-set with no
     * same-caller special case.
     *
     * <p>Deliberately does NOT touch {@link AgentLoopRegistry#getOrCreate} — the pre-redesign
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
     * @param label       human-readable identity shown on the owner's console/HUD (e.g. "Claude Code · 3f2a")
     * @param ttlSeconds  requested lease TTL; the server clamps it (60 s – 20 min) and renews it on
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
                            // running (W8's gates only stop a NEW turn / a NEW tool ship) — stop
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
     * Release external control of {@code companion} — a real server round trip ({@code
     * op=RELEASE} out, confirmation via {@link ClientControl#awaitRelease}). Reports honestly:
     * {@code false} immediately (no network round trip at all) when this client does not
     * currently believe {@code sessionToken} is the live lease for {@code companion} — sending it
     * anyway would just be refused server-side (token mismatch) for the same reason. On a
     * matching token, sends the release and completes once the server's next control-state push
     * confirms the token is no longer live (someone else releasing/expiring it first counts too —
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
     * Keep the lease from expiring without invoking a tool — for a caller expecting to think
     * longer than the granted TTL between actions. Sends {@code op=HEARTBEAT}; the returned
     * boolean is this client's OWN belief that {@code sessionToken} is still the live lease at
     * send time (a cheap local read, not a round-trip confirmation like {@link #acquire}/{@link
     * #release} — a heartbeat is routine upkeep, not a decision the caller blocks on).
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
     * Run one tool for {@code companion} directly — the same tools the built-in
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
     * AgentLoopRegistry#harvestExternal} before completing the caller's future — external tool
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
                f.complete(TaskResult.fail("you do not hold this companion — currently controlled by " + holder).toJson());
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

    /** {@link ClientControl.LeaseLostListener} registered once in the static initializer — fails
     *  every still-outstanding {@link #invoke} future for {@code companion} with an explanatory
     *  result instead of leaving the caller to discover the loss on its own. */
    private static void onLeaseLost(UUID companion, String reason) {
        List<CompletableFuture<String>> calls = OUTSTANDING.remove(companion);
        if (calls == null) return;
        String msg = "external control lost: " + reason;
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
