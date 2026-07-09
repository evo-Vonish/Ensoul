package com.dwinovo.numen.mcp;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;

/**
 * NeoForge entry point — the MCP server is client-only; on a dedicated server
 * this mod loads and does nothing.
 */
@Mod(Constants.MOD_ID)
public class NumenMcpNeoForge {

    public NumenMcpNeoForge() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            NumenMcp.initClient(FMLPaths.CONFIGDIR.get());
        }
    }
}
