package com.dwinovo.numen;

import com.dwinovo.numen.network.NumenNetwork;
import com.dwinovo.numen.platform.NeoForgeNumenConfig;
import com.dwinovo.numen.platform.NeoForgeNetworkChannel;
import com.dwinovo.numen.platform.Services;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@Mod(Constants.MOD_ID)
public class NumenMod {

    public NumenMod(IEventBus eventBus, ModContainer container) {
        eventBus.addListener(NumenMod::registerPayloads);

        // Register the TOML config spec — NeoForge handles file creation +
        // hot-reload from this point on. SPEC is built lazily in the
        // NeoForgeNumenConfig static initialiser so referencing it here is
        // safe (no I/O happens until the world loads).
        container.registerConfig(ModConfig.Type.COMMON, NeoForgeNumenConfig.SPEC);

        // Queue payload registrations into NeoForgeNetworkChannel; the queue
        // flushes when RegisterPayloadHandlersEvent fires (see below).
        NumenNetwork.register();

        // Dev: /numen_summon — create a companion fake player at the caller.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.RegisterCommandsEvent e) ->
                com.dwinovo.numen.entity.NumenCommands.register(e.getDispatcher()));
        // When an owner logs in, bring their dormant companions back.
        NeoForge.EVENT_BUS.addListener(NumenMod::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(NumenMod::onPlayerChangedDimension);
        // W7: the owner-logout hook — see the sibling registration + rationale in onPlayerLoggedOut below.
        NeoForge.EVENT_BUS.addListener(NumenMod::onPlayerLoggedOut);

        CommonClass.init();
        Constants.LOG.info("Numen mod initialised on NeoForge.");
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        if (Services.NETWORK instanceof NeoForgeNetworkChannel ch) {
            ch.flushPending(event);
        }
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player instanceof com.dwinovo.numen.entity.NumenPlayer) return;  // not the companion itself
        MinecraftServer server = player.level().getServer();
        if (server != null) {
            com.dwinovo.numen.entity.Companions.respawnAllOwnedBy(server, player.getUUID());
            com.dwinovo.numen.entity.Companions.syncRosterToOwner(server, player);
        }
    }

    /**
     * W7: release every control lease the departing owner holds. There is NO server-side logout handler
     * on either loader before this (a repo-wide search for LoggedOut / DISCONNECT / ServerPlayConnectionEvents
     * found only the two CLIENT hooks and the companion fake player's no-op connection) — this is the
     * single most important missing hook for automatic release. Unconditional: the MCP bridge is hosted
     * INSIDE the owner's own game client (NumenMcp.initClient and the agent-loop machinery are client-side),
     * so once that client disconnects, every external agent driving that owner's companions is PROVABLY
     * gone — waiting for the lease TTL instead would leave bodies frozen for minutes with nobody left who
     * could release them. Guarded against the companion body itself logging out (not an owner logout at
     * all — {@code CompanionLifecycle.onRemove} already handles a companion leaving the world); releaseAllOwnedBy
     * itself no-ops per-companion when nothing is leased, so this call is always safe.
     */
    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (sp instanceof com.dwinovo.numen.entity.NumenPlayer) return;  // not the companion itself
        MinecraftServer server = sp.level().getServer();
        if (server != null) {
            com.dwinovo.numen.entity.ControlRegistry.releaseAllOwnedBy(server, sp.getUUID(), "owner disconnected");
        }
    }

    /** The companion crossed a portal on its own — tell its brain (ambient world event). */
    private static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof com.dwinovo.numen.entity.NumenPlayer ap) {
            com.dwinovo.numen.entity.Companions.onDimensionChanged(ap);
        }
    }
}
