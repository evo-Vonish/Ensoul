package com.dwinovo.numen.core.net;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Client-to-server payload: "cancel everything my entity is doing". Sent by
 * {@code EntityAgentLoop.abort()} (the owner's panel Stop button, and the
 * built-in loop's own death/interrupt teardown) alongside the client-side turn
 * teardown, so the interrupt stops the <em>body</em> (the running task:
 * walking, mining, placing), not just the model conversation.
 *
 * <h2>Trust model</h2>
 * Owner check first — no distance check, deliberately: the owner must be able
 * to stop a companion that has wandered far away. On top of ownership, a
 * SECOND, independent check gates whose <em>work</em> may be cancelled (D1):
 * ownership says who owns the companion, not which brain is currently driving
 * it, so a cancel from a brain that does not hold the body must not be allowed
 * to silently kill another brain's in-flight task. {@code ownerForce} is the
 * human override — the owner's own panel Stop is always allowed to land
 * regardless of which brain currently holds the lease (see the design doc's
 * gatedSites: "the owner's panel Stop sends an explicit force flag that is
 * always accepted"); a brain's own cancel (no force) must instead present the
 * control token it was issued and pass {@link
 * com.dwinovo.numen.entity.ControlRegistry#authorize}, exactly like {@link
 * ExecuteToolPayload}.
 *
 * <p>The server-side {@code CANCELLED} results this produces are shipped back
 * via {@link TaskResultPayload} as usual; the client agent loop has already
 * synthesized "interrupted by owner" results for those tool-call ids and drops
 * the real ones as late arrivals. This payload's job is purely the body stop.
 */
public record CancelTasksPayload(UUID entityUuid, String controlToken, boolean ownerForce)
        implements CustomPacketPayload {

    /** Same bound as {@link ExecuteToolPayload#MAX_CONTROL_TOKEN_LENGTH} — both carry the same token shape. */
    public static final int MAX_CONTROL_TOKEN_LENGTH = 64;

    public static final Type<CancelTasksPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "cancel_tasks"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CancelTasksPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, CancelTasksPayload::entityUuid,
                    ByteBufCodecs.stringUtf8(MAX_CONTROL_TOKEN_LENGTH), CancelTasksPayload::controlToken,
                    ByteBufCodecs.BOOL, CancelTasksPayload::ownerForce,
                    CancelTasksPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Handler invoked on the server main thread. */
    public static void handle(CancelTasksPayload p, ServerPlayer player) {
        // Cross-dimension lookup: the owner must be able to stop a companion
        // that has wandered into another dimension or out of view distance.
        NumenPlayer numen = NumenPlayer.findByUuid(player.level().getServer(), p.entityUuid());
        if (numen == null) {
            Constants.LOG.debug("[numen-net] cancel_tasks for unknown entity {}", p.entityUuid());
            return;
        }
        if (!numen.isOwnedByPlayer(player.getUUID())) {
            Constants.LOG.warn("[numen-net] ✗ cancel_tasks rejected from {}: not the owner",
                    player.getName().getString());
            return;
        }
        // Control gate (D1): skipped only for the human override (ownerForce) — the owner outranks any
        // brain, always. Otherwise the caller must currently hold the body (or the body must be at
        // baseline BUILTIN with an empty token): one brain must not be able to silently cancel another's
        // queued work just by knowing the owner's connection. No replyError-style ack exists for this
        // payload (it is a fire-and-forget body-stop signal, not an awaited tool_call), so a rejection is
        // simply logged — the caller isn't left dead-waiting on anything, unlike ExecuteToolPayload.
        if (!p.ownerForce()) {
            var decision = com.dwinovo.numen.entity.ControlRegistry.authorize(
                    player.level().getServer(), p.entityUuid(), player.getUUID(), p.controlToken());
            if (!decision.allowed()) {
                Constants.LOG.warn("[numen-net] ✗ cancel_tasks rejected from {}: {}",
                        player.getName().getString(), decision.reason());
                return;
            }
        }
        com.dwinovo.numen.core.task.CompanionTickDispatcher.cancelFor(numen);
        Constants.LOG.info("[numen-net] ✓ cancel_tasks on entity {} for {}",
                p.entityUuid(), player.getName().getString());
    }
}
