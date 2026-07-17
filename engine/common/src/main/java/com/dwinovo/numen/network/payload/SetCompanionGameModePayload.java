package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.Companions;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.UUID;

/**
 * Client → Server: the owner flipped a companion's GAME MODE toggle in the G-panel Settings tab
 * (survival ⇄ creative). Applies to the persisted per-companion state AND the live body if spawned
 * (see {@link Companions#setGameMode}); a dormant companion picks it up on its next spawn.
 */
public record SetCompanionGameModePayload(UUID uuid, GameType mode) implements CustomPacketPayload {

    public static final Type<SetCompanionGameModePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "set_companion_game_mode"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetCompanionGameModePayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, SetCompanionGameModePayload::uuid,
                    GameType.STREAM_CODEC, SetCompanionGameModePayload::mode,
                    SetCompanionGameModePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Server main thread. Only the companion's owner may change its game mode. */
    public static void handle(SetCompanionGameModePayload p, ServerPlayer owner) {
        MinecraftServer server = ((ServerLevel) owner.level()).getServer();
        if (server == null || p.uuid() == null || p.mode() == null) return;
        if (!Companions.isOwner(server, p.uuid(), owner.getUUID())) return;   // not the caller's companion
        Companions.setGameMode(server, p.uuid(), p.mode());
        Companions.syncRosterToOwner(server, owner);   // push the new capability state back to the panel
    }
}
