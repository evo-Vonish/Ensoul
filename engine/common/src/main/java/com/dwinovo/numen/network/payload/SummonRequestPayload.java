package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.Companions;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client → Server: the owner asked to summon a companion by name from the panel's
 * "+" button. Mirrors the {@code /numen player summon} command — summon is
 * idempotent per (owner, name), so re-summoning an existing name just wakes it.
 */
public record SummonRequestPayload(String name) implements CustomPacketPayload {

    /**
     * The wire codec's DECODE bound — deliberately still 32, NOT the real 16-character limit. Dropping
     * this to 16 would make an oversize name fail as a raw {@code DecoderException} during decode,
     * before {@link #handle} ever runs — a worse failure than the clean, typed rejection this payload
     * now gets from {@link Companions#summon}. The actual cap is {@link Companions#MAX_NAME_LENGTH};
     * see that method's javadoc for the full reasoning (vanilla's {@code PLAYER_NAME} codec).
     */
    public static final int MAX_NAME = 32;

    public static final Type<SummonRequestPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "summon_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SummonRequestPayload> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.stringUtf8(MAX_NAME), SummonRequestPayload::name,
                    SummonRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Server main thread. All validation (blank / too long / name already taken) now lives in
     * {@link Companions#summon} — the single authoritative convergence point shared with
     * {@code /numen player summon} — so a modified client that skips its own field validation gains
     * nothing here. On rejection this used to silently {@code return}, leaving the owner staring at a
     * prompt that appeared to hang; it now answers with a chat message naming the reason. A dedicated
     * {@code SummonResultPayload} (driving inline error text in the creation dialog) is a later wave's
     * job — this is the minimal failure channel for now, using vanilla's own system-message delivery so
     * nothing new needs registering in {@code NumenNetwork}.
     */
    public static void handle(SummonRequestPayload p, ServerPlayer owner) {
        String name = p.name() == null ? "" : p.name();
        ServerLevel level = (ServerLevel) owner.level();
        MinecraftServer server = level.getServer();
        Companions.SummonOutcome outcome = Companions.summon(server, owner.getUUID(), name, level, owner.position());
        if (!outcome.success()) {
            owner.sendSystemMessage(Component.literal("[Numen] " + outcome.reason()));
            return;
        }
        Companions.syncRosterToOwner(server, owner);   // push the new roster to the owner
    }
}
