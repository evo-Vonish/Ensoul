package com.dwinovo.numen.mcp;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Fabric client entry point — the MCP server is client-only, because companions
 * (and their tool registry) live in the owner's game client.
 */
public class NumenMcpFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        NumenMcp.initClient(FabricLoader.getInstance().getConfigDir());
    }
}
