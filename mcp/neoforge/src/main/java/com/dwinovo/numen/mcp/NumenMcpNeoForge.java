package com.dwinovo.numen.mcp;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;

/**
 * NeoForge entry point — the MCP server is client-only, because companions (and
 * their tool registry) live in the owner's game client. The mod class is gated to
 * {@link Dist#CLIENT}, mirroring the engine's own client mod class, so on a
 * dedicated server it isn't constructed and does nothing.
 */
@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public class NumenMcpNeoForge {

    public NumenMcpNeoForge(IEventBus modBus) {
        NumenMcp.initClient(FMLPaths.CONFIGDIR.get());
    }
}
