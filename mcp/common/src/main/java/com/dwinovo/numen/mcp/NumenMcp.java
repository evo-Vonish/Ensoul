package com.dwinovo.numen.mcp;

import com.dwinovo.numen.mcp.transport.McpHttpServer;

import java.nio.file.Path;

/**
 * Loader-agnostic core — both entry points call {@link #initClient} once during
 * client init.
 *
 * <p>This mod is a thin adapter over numen-api's {@code NumenActuator}: it stands
 * up an MCP (Model Context Protocol) server so an external agent (Claude Code,
 * Codex, Kimi Code, or Claude Desktop via the {@code mcp-remote} stdio bridge) can
 * list the owner's companions, take control of a body, and call its tools directly
 * — the external agent is the brain, the companion is its hands and eyes.
 * Client-only, because companions and their tool registry live in the owner's game
 * client.
 *
 * <p>Each connecting brain gets its own session and its own control leases, so
 * several agents can drive different companions at the same time without their
 * leases colliding — see {@code McpSession}.
 */
public final class NumenMcp {

    private static McpHttpServer server;

    private NumenMcp() {}

    public static void initClient(Path configDir) {
        McpConfig cfg = McpConfig.load(configDir.resolve("numen").resolve("mcp_server.json"));
        if (!cfg.enabled()) {
            Constants.LOG.info("[numen-mcp] disabled in config (config/numen/mcp_server.json)");
            return;
        }
        server = new McpHttpServer(cfg);
        try {
            server.start();
            Constants.LOG.info("[numen-mcp] MCP server up on http://{}:{}/mcp — reach it from Claude Desktop via "
                    + "`npx mcp-remote http://{}:{}/mcp`", cfg.host(), cfg.port(), cfg.host(), cfg.port());
        } catch (Exception ex) {
            Constants.LOG.error("[numen-mcp] failed to start MCP server on {}:{} — {}",
                    cfg.host(), cfg.port(), ex.toString());
            server = null;
        }
    }
}
