package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.Companions;
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
 * Client → Server: the owner flipped a companion's OP master switch in the G-panel Settings tab.
 * The engine only persists the flag ({@link Companions#setOpEnabled}); the tool pack's {@code run_command}
 * consults it — OP off ⇒ command permission floored to {@code all}; OP on ⇒ {@code min(owner, /numenperm tier)}.
 */
public record SetCompanionOpPayload(UUID uuid, boolean opEnabled) implements CustomPacketPayload {

    public static final Type<SetCompanionOpPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "set_companion_op"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetCompanionOpPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, SetCompanionOpPayload::uuid,
                    ByteBufCodecs.BOOL, SetCompanionOpPayload::opEnabled,
                    SetCompanionOpPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Server main thread. Only the companion's owner may flip its OP switch. */
    public static void handle(SetCompanionOpPayload p, ServerPlayer owner) {
        MinecraftServer server = ((ServerLevel) owner.level()).getServer();
        if (server == null || p.uuid() == null) return;
        if (!Companions.isOwner(server, p.uuid(), owner.getUUID())) return;   // not the caller's companion
        Companions.setOpEnabled(server, p.uuid(), p.opEnabled());
        Companions.syncRosterToOwner(server, owner);   // push the new capability state back to the panel
    }
}
