package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.ControlRegistry;
import com.dwinovo.numen.entity.ControlRegistry.ControlState;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Client → Server: the owner's client asking {@link ControlRegistry} to change who drives one of
 * their companions. This is a REQUEST, never an assertion — the only way a client's view of
 * control state changes is the {@link ControlStatePayload} the server sends back (see that
 * class's doc comment, and the design doc's "CLIENT DISAGREEMENT" note). There is no packet by
 * which a client can declare itself in control.
 *
 * <p>{@code op} selects the verb (see the {@code OP_*} constants); the other fields are read
 * only by the verbs that need them — e.g. {@code baseline} is meaningless for {@code
 * OP_ACQUIRE}, {@code sessionToken} is meaningless for {@code OP_ACQUIRE} (the server MINTS the
 * token; the caller doesn't have one yet).
 */
public record ControlRequestPayload(UUID companion, byte op, String controllerId, String sessionToken,
                                     String label, int ttlSeconds, byte baseline)
        implements CustomPacketPayload {

    /** Take the lease: BUILTIN/NONE → EXTERNAL. Refused (compare-and-set) if one is already live. */
    public static final byte OP_ACQUIRE = 0;
    /** Give the lease back: EXTERNAL → baseline. Requires {@code sessionToken} to match. */
    public static final byte OP_RELEASE = 1;
    /** Keep the live lease from expiring. Requires {@code sessionToken} to match. */
    public static final byte OP_HEARTBEAT = 2;
    /** The human override — the owner panel's "Release" button. No token required; always wins. */
    public static final byte OP_FORCE_RELEASE = 3;
    /** Set the owner-chosen resting state (BUILTIN or NONE) for when no lease is held. */
    public static final byte OP_SET_BASELINE = 4;

    public static final Type<ControlRequestPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "control_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ControlRequestPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, ControlRequestPayload::companion,
                    ByteBufCodecs.BYTE, ControlRequestPayload::op,
                    ByteBufCodecs.stringUtf8(64), ControlRequestPayload::controllerId,
                    ByteBufCodecs.stringUtf8(64), ControlRequestPayload::sessionToken,
                    ByteBufCodecs.stringUtf8(64), ControlRequestPayload::label,
                    ByteBufCodecs.VAR_INT, ControlRequestPayload::ttlSeconds,
                    ByteBufCodecs.BYTE, ControlRequestPayload::baseline,
                    ControlRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Server main thread. Mirrors {@link SetCompanionOpPayload#handle} line for line: resolve the
     * server, null-check it and the target companion, gate on ownership, return silently on
     * failure (there is no caller to answer — an unowned or unresolved companion is not this
     * player's business), dispatch to {@link ControlRegistry}, then push a fresh sync so the
     * requester's console/panel reflects the outcome even for verbs {@code ControlRegistry}
     * itself doesn't sync (e.g. a HEARTBEAT that only bumps a heartbeat clock).
     */
    public static void handle(ControlRequestPayload p, ServerPlayer owner) {
        MinecraftServer server = ((ServerLevel) owner.level()).getServer();
        if (server == null || p.companion() == null) return;
        if (!Companions.isOwner(server, p.companion(), owner.getUUID())) return;   // not the caller's companion
        switch (p.op()) {
            case OP_ACQUIRE -> ControlRegistry.acquire(server, p.companion(), owner.getUUID(),
                    p.controllerId(), p.label(), p.ttlSeconds(), ControlRegistry.mintToken());
            case OP_RELEASE -> ControlRegistry.release(server, p.companion(), p.sessionToken(), "released");
            case OP_HEARTBEAT -> ControlRegistry.renew(p.companion(), p.sessionToken());
            case OP_FORCE_RELEASE -> ControlRegistry.forceRelease(server, p.companion(), "owner force release");
            case OP_SET_BASELINE -> {
                ControlState baseline = decodeBaseline(p.baseline());
                if (baseline != null) ControlRegistry.setBaseline(p.companion(), baseline);
            }
            default -> {   // unknown op (client ahead of server) — ignore, still sync below
            }
        }
        ControlRegistry.syncToOwner(server, owner);
    }

    /** {@code baseline} may only ever be BUILTIN or NONE (see {@link ControlRegistry#setBaseline});
     *  anything else (a stray EXTERNAL byte, or an out-of-range value from a mismatched client) is
     *  rejected here rather than let {@code setBaseline} throw out of a network handler. */
    private static ControlState decodeBaseline(byte wire) {
        ControlState[] values = ControlState.values();
        if (wire < 0 || wire >= values.length) return null;
        ControlState decoded = values[wire];
        return decoded == ControlState.BUILTIN || decoded == ControlState.NONE ? decoded : null;
    }
}
