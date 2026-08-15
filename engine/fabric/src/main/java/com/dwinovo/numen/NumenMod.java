package com.dwinovo.numen;

import com.dwinovo.numen.network.NumenNetwork;
import net.fabricmc.api.ModInitializer;
import net.minecraft.server.level.ServerPlayer;

public class NumenMod implements ModInitializer {

    @Override
    public void onInitialize() {
        NumenNetwork.register();

        // Dev: /numen_summon — create a companion fake player at the caller.
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, registry, env) ->
                        com.dwinovo.numen.entity.NumenCommands.register(dispatcher));

        // When an owner logs in, bring their dormant companions back.
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> {
                    ServerPlayer player = handler.getPlayer();
                    if (player instanceof com.dwinovo.numen.entity.NumenPlayer) return;  // not the companion itself
                    com.dwinovo.numen.entity.Companions.respawnAllOwnedBy(server, player.getUUID());
                    com.dwinovo.numen.entity.Companions.syncRosterToOwner(server, player);
                });

        // W7: the owner-logout hook — release every control lease the departing owner holds.
        // No server-side logout handler existed on either loader before this (a repo-wide search for
        // LoggedOut / DISCONNECT / ServerPlayConnectionEvents found only the two CLIENT hooks and the
        // companion fake player's no-op connection). This is the single most important missing hook for
        // automatic release: the MCP bridge is hosted INSIDE the owner's own game client (NumenMcp.initClient
        // and the agent-loop machinery are client-side), so once that client disconnects, every external
        // agent driving that owner's companions is PROVABLY gone — waiting for the lease TTL instead would
        // leave bodies frozen for minutes with nobody left who could release them. Unconditional: releaseAllOwnedBy
        // itself no-ops per-companion when nothing is leased, so this is always safe to call.
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> {
                    ServerPlayer player = handler.getPlayer();
                    if (player == null || player instanceof com.dwinovo.numen.entity.NumenPlayer) return;  // not the companion itself
                    com.dwinovo.numen.entity.ControlRegistry.releaseAllOwnedBy(server, player.getUUID(), "owner disconnected");
                });

        // The companion crossed a portal on its own — tell its brain (ambient world event).
        net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register(
                (player, origin, destination) -> {
                    if (player instanceof com.dwinovo.numen.entity.NumenPlayer ap) {
                        com.dwinovo.numen.entity.Companions.onDimensionChanged(ap);
                    }
                });

        CommonClass.init();
        Constants.LOG.info("Numen mod initialised on Fabric.");
    }
}
