package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.client.agent.ClientControl;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.UUID;

/**
 * Server → Client: a FULL snapshot of the control state of every companion the receiving player
 * owns — see the design doc's control-authority state machine. This is the sync half of {@link
 * com.dwinovo.numen.entity.ControlRegistry}; the registry never syncs anything itself except by
 * building and sending one of these.
 *
 * <h2>Level-triggered, not edge-triggered</h2>
 * Every push is a complete replacement of the client's view ({@code ClientControl.replaceAll}),
 * never a delta and never merged onto what the client already believed. That is deliberate: this
 * packet is sent on every {@code ControlRegistry} transition, on owner login, AND as a keep-alive
 * every 20 server ticks ({@code ControlRegistry.tick}), so a single dropped packet can self-correct
 * on the very next resend instead of being able to leave a body permanently gated. A companion
 * missing from a given snapshot is a companion the sender didn't include for the same reasons
 * {@code CompanionListPayload}/{@code NumenRoster} treat a missing entry as "not present" — the
 * client drops it rather than let a stale entry linger.
 */
public record ControlStatePayload(long serverGameTime, List<Entry> states) implements CustomPacketPayload {

    /** Cap defends against absurd input; matches {@link CompanionListPayload#MAX}. */
    public static final int MAX = 64;

    /**
     * One companion's control line. {@code state} is the wire encoding of {@code
     * ControlRegistry.ControlState}'s ordinal (0=NONE, 1=BUILTIN, 2=EXTERNAL — see that enum's
     * doc comment, which is the single place the mapping is declared authoritative).
     *
     * <p>{@code controllerLabel}/{@code sessionToken}/{@code ttlRemainingTicks}/{@code idleTicks}
     * are only meaningful while {@code state == EXTERNAL}; empty/zero otherwise. {@code
     * sessionToken} is populated ONLY in the copy sent to the companion's OWNER (every controller
     * is hosted inside the owner's own client, so the owner is always who needs it) — it is what
     * {@code CoreServerTools.ship} attaches to outgoing built-in tool calls so the server can tell
     * which brain a call came from.
     */
    public record Entry(UUID companion, byte state, String controllerLabel, String sessionToken,
                         int ttlRemainingTicks, int idleTicks) {
        static final StreamCodec<RegistryFriendlyByteBuf, Entry> CODEC =
                StreamCodec.composite(
                        UUIDUtil.STREAM_CODEC, Entry::companion,
                        ByteBufCodecs.BYTE, Entry::state,
                        ByteBufCodecs.stringUtf8(64), Entry::controllerLabel,
                        ByteBufCodecs.stringUtf8(64), Entry::sessionToken,
                        ByteBufCodecs.VAR_INT, Entry::ttlRemainingTicks,
                        ByteBufCodecs.VAR_INT, Entry::idleTicks,
                        Entry::new);
    }

    public static final Type<ControlStatePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "control_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ControlStatePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, ControlStatePayload::serverGameTime,
                    Entry.CODEC.apply(ByteBufCodecs.list(MAX)), ControlStatePayload::states,
                    ControlStatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client main thread. A full replacement, never a merge — see the class doc comment. */
    public static void handle(ControlStatePayload p) {
        ClientControl.instance().replaceAll(p);
    }
}
