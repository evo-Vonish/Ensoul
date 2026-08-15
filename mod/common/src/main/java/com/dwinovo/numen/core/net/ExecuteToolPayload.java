package com.dwinovo.numen.core.net;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Client-to-server payload: "the LLM running on my client side decided to
 * call this tool — please execute it on my entity".
 *
 * <h2>Trust model</h2>
 * The server treats this as unvalidated input. Validation chain:
 * <ol>
 *   <li>Target must be an {@link com.dwinovo.numen.entity.NumenPlayer} (searched across ALL
 *       dimensions — a working companion may be in the Nether while the owner
 *       waits in the overworld).</li>
 *   <li>Sender must be the entity's owner (UUID comparison, cross-dimension safe).</li>
 *   <li>Tool name must resolve to a registered {@link NumenTool}.</li>
 *   <li>Arguments JSON must parse and pass the tool's
 *       {@code toTaskRecord} validation.</li>
 * </ol>
 * There is deliberately NO owner-distance check: the whole point of the
 * chunk-ticket system is that the companion keeps working far away, and the
 * tools act at the <em>entity's</em> location with the entity's own abilities
 * — owner distance grants nothing exploitable. (Ownership is the auth.)
 *
 * <p>Any failure path emits an immediate
 * {@link TaskResultPayload} with {@code success:false} back to the sender so
 * the client agent loop can keep the conversation consistent — silently
 * dropping a tool call would leave the LLM waiting forever and the chain
 * stuck.
 *
 * <h2>Query fast path</h2>
 * Read-only perception tools never touch the
 * {@code TaskQueue} — they execute synchronously right here on the tick
 * thread and the result ships back in the same tick. Queueing them behind a
 * running {@code move_to} would turn "what's my HP" into a minute-long wait.
 *
 * <h2>Wire format</h2>
 * Fixed-shape strings (Identifier for compactness on the tool name even
 * though tools live under a single namespace; this is forward-compatible for
 * multi-namespace tool registries). The arguments arrive as the raw JSON
 * string the LLM emitted; server parses with Gson and re-validates.
 *
 * <h2>Control token (D1)</h2>
 * {@code controlToken} names WHICH BRAIN on the sender's client is making this call — the built-in
 * brain sends {@code ""} ("my owner's built-in brain"), an external controller sends the session
 * token it was handed by {@code ControlRequestPayload}'s ACQUIRE. This is deliberately a WIRE-FORMAT
 * field, not inferred from {@code toolCallId}'s shape: a {@code toolCallId} prefix can distinguish
 * "the built-in brain" from "some external caller" but not one external caller from another, which is
 * the entire point of the mutual-exclusion invariant this field exists to enforce (see the design
 * doc's destructiveDecisions D1 — the rejected alternative). Checked against {@code ControlRegistry}
 * in {@link #handle}; this record change is why client and server of this mod MUST ship together.
 */
public record ExecuteToolPayload(UUID entityUuid,
                                  String toolCallId,
                                  String toolName,
                                  String argumentsJson,
                                  String controlToken) implements CustomPacketPayload {

    public static final int MAX_TOOL_CALL_ID_LENGTH = 128;
    public static final int MAX_TOOL_NAME_LENGTH = 128;
    public static final int MAX_ARGUMENTS_JSON_LENGTH = 16 * 1024;
    public static final int MAX_CONTROL_TOKEN_LENGTH = 64;

    public static final Type<ExecuteToolPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "execute_tool"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ExecuteToolPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, ExecuteToolPayload::entityUuid,
                    ByteBufCodecs.stringUtf8(MAX_TOOL_CALL_ID_LENGTH), ExecuteToolPayload::toolCallId,
                    ByteBufCodecs.stringUtf8(MAX_TOOL_NAME_LENGTH), ExecuteToolPayload::toolName,
                    ByteBufCodecs.stringUtf8(MAX_ARGUMENTS_JSON_LENGTH), ExecuteToolPayload::argumentsJson,
                    ByteBufCodecs.stringUtf8(MAX_CONTROL_TOKEN_LENGTH), ExecuteToolPayload::controlToken,
                    ExecuteToolPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Handler invoked on the server main thread. */
    public static void handle(ExecuteToolPayload p, ServerPlayer player) {
        String who = player.getName().getString();
        Constants.LOG.debug("[numen-net] ← execute_tool from {} entity={} tool={} id={} args_chars={}",
                who, p.entityUuid(), p.toolName(), p.toolCallId(), p.argumentsJson().length());

        // -- 0. companion player body (the new architecture). Resolve it (or
        //       respawn it from the registry on a cold start) and run the tool
        //       through the CompanionTickDispatcher instead of the Mob GoalSelector.
        var server = player.level().getServer();
        // Authorize BEFORE the respawn below, not after. The respawn materialises a dormant body
        // into the world — a real, persistent side effect — and it used to run for ANY caller,
        // because ownership was only checked afterwards inside handleCompanion. A stranger could
        // therefore force-spawn someone else's companion at will and merely receive "not the owner"
        // for their trouble. Companions.isOwner resolves from the live body when one is loaded and
        // from the registry entry when it isn't, so it answers correctly while still dormant.
        if (!com.dwinovo.numen.entity.Companions.isOwner(server, p.entityUuid(), player.getUUID())) {
            replyError(player, p, "not the owner");
            return;
        }
        // Control gate (D1): ownership answers "whose companion is this" — the check above; control
        // answers "which brain may drive it right now", and the two are independent (an owner who
        // force-released control to NONE, or whose external session holds it, still OWNS the
        // companion, but this caller may still not act on it). Placed here for the exact same reason
        // the ownership check above was moved ahead of the respawn: the respawn below materialises a
        // dormant body, a real persistent side effect, and a gate placed after a side effect repeats
        // that bug verbatim. ControlRegistry.authorize never touches the world, so it is safe to run
        // before the respawn even though the companion may not be live yet — it resolves ownership and
        // lease state from the registry, not the live body. Rejection replies through the existing
        // replyError path so neither brain ever dead-waits on an unanswered tool_call id.
        var controlDecision = com.dwinovo.numen.entity.ControlRegistry.authorize(
                server, p.entityUuid(), player.getUUID(), p.controlToken());
        if (!controlDecision.allowed()) {
            replyError(player, p, controlDecision.reason());
            return;
        }
        com.dwinovo.numen.entity.NumenPlayer companion =
                com.dwinovo.numen.entity.NumenPlayer.findByUuid(server, p.entityUuid());
        if (companion == null) {
            companion = com.dwinovo.numen.entity.Companions.respawn(server, p.entityUuid());
        }
        if (companion != null) {
            handleCompanion(p, player, companion);
            return;
        }
        replyError(player, p, "companion not found (never summoned, or its data is gone)");
    }

    /**
     * Run a tool against the companion player body: owner-check, then enqueue
     * world-action tools onto its queue for the {@code CompanionTickDispatcher}.
     * Query/perception tools aren't ported to the player body yet (Phase 0 wires
     * move_to + auto_mine), so they reply with a clear not-yet message.
     */
    private static void handleCompanion(ExecuteToolPayload p, ServerPlayer player,
                                        com.dwinovo.numen.entity.NumenPlayer companion) {
        // Redundant with the pre-respawn gate in handle(), kept as defence in depth: this one sees
        // the live body directly, so it also catches an ownership change between the two checks.
        if (!companion.isOwnedByPlayer(player.getUUID())) {
            replyError(player, p, "not the owner");
            return;
        }
        // Same defence-in-depth reasoning for control (D1): a lease can expire, be released, or be
        // force-released in the tick between handle()'s gate and this call. authorize() also RENEWS an
        // EXTERNAL lease on success (a fresh heartbeat), so re-running it here is not wasted work even
        // on the happy path — every call from the current holder keeps its own lease fresh, on top of
        // the per-tick RUNNING-task renewal a queued task gets from CompanionTickDispatcher (W6).
        var server = player.level().getServer();
        var controlDecision = com.dwinovo.numen.entity.ControlRegistry.authorize(
                server, p.entityUuid(), player.getUUID(), p.controlToken());
        if (!controlDecision.allowed()) {
            replyError(player, p, controlDecision.reason());
            return;
        }
        NumenTool tool = ToolRegistry.get(p.toolName());
        if (tool == null) {
            replyError(player, p, "unknown tool: " + p.toolName());
            return;
        }
        JsonObject args;
        try {
            args = JsonParser.parseString(p.argumentsJson()).getAsJsonObject();
        } catch (RuntimeException ex) {
            replyError(player, p, "invalid arguments JSON: " + ex.getMessage());
            return;
        }
        // Run the tool against the live companion: a query replies now, a world action
        // enqueues and its result returns via the task lifecycle. Server execution isn't
        // part of the MC-free NumenTool contract, so dispatch via core's ServerNumenTool base.
        java.util.function.Consumer<String> reply = json ->
                com.dwinovo.numen.platform.Services.NETWORK.sendToPlayer(player,
                        new TaskResultPayload(p.entityUuid(), p.toolCallId(), json));
        try {
            if (tool instanceof com.dwinovo.numen.core.tool.ServerNumenTool st) {
                // The single point where every server tool's args are read: pull the optional
                // per-call timeout_seconds cap and bind it for ctx() to pick up, so ToolContext.deadline
                // can tighten the task's self-estimated budget. Instant/query tools never build a
                // TaskRecord, so the bound cap is simply never consulted for them.
                com.dwinovo.numen.core.tool.ServerNumenTool.bindMaxSeconds(readTimeoutSeconds(args));
                try {
                    st.runOnServer(p.toolCallId(), args, companion, reply);
                } finally {
                    com.dwinovo.numen.core.tool.ServerNumenTool.unbindMaxSeconds();
                }
            } else {
                replyError(player, p, "tool not server-runnable: " + p.toolName());
            }
        } catch (RuntimeException ex) {
            replyError(player, p, "invalid arguments: " + ex.getMessage());
        }
    }

    /**
     * The optional {@code timeout_seconds} time-box the model may put on any tool call
     * (universally injected into every tool schema at the engine serialization layer).
     * A positive integer caps this call's runtime; absent / non-positive / unparseable
     * yields {@code 0} (no cap). This is the one place the value is read off the wire.
     * The legacy {@code max_seconds} key is still honoured so persisted conversations
     * (and a mid-rename model habit) replay cleanly.
     */
    private static int readTimeoutSeconds(JsonObject args) {
        int v = positiveInt(args, "timeout_seconds");
        return v > 0 ? v : positiveInt(args, "max_seconds");
    }

    private static int positiveInt(JsonObject args, String key) {
        if (args.has(key) && args.get(key).isJsonPrimitive()) {
            try {
                int v = args.get(key).getAsInt();
                return v > 0 ? v : 0;
            } catch (RuntimeException ignored) { /* not an int → no cap */ }
        }
        return 0;
    }

    public static void replyError(ServerPlayer player, ExecuteToolPayload p, String message) {
        Constants.LOG.warn("[numen-net] ✗ execute_tool rejected from {}: tool={} id={} reason={}",
                player.getName().getString(), p.toolName(), p.toolCallId(), message);
        String json = TaskResult.fail(message).toJson();
        com.dwinovo.numen.platform.Services.NETWORK.sendToPlayer(player,
                new TaskResultPayload(p.entityUuid(), p.toolCallId(), json));
    }
}
