package com.dwinovo.numen.mcp;

import java.nio.file.Path;

/**
 * Loader-agnostic core — both entry points call {@link #initClient} once during
 * client init.
 *
 * <p>This mod is a thin adapter over numen-api's {@code NumenActuator}: it stands
 * up an MCP (Model Context Protocol) server so an external agent (Claude Desktop,
 * via the {@code mcp-remote} stdio bridge) can list the owner's companions, take
 * control of a body, and call its tools directly — the external agent is the
 * brain, the companion is its hands and eyes. Client-only, because companions
 * and their tool registry live in the owner's game client.
 */
public final class NumenMcp {

    private static McpServer server;

    private NumenMcp() {}

    public static void initClient(Path configDir) {
        McpConfig cfg = McpConfig.load(configDir.resolve("numen").resolve("mcp_server.json"));
        if (!cfg.enabled()) {
            Constants.LOG.info("[numen-mcp] disabled in config (config/numen/mcp_server.json)");
            return;
        }
        server = new McpServer(cfg);
        try {
            server.start();
            Constants.LOG.info("[numen-mcp] MCP server up on http://{}:{}/mcp — reach it from Claude Desktop via "
                    + "`npx mcp-remote http://{}:{}/mcp`", cfg.host(), cfg.port(), cfg.host(), cfg.port());
            // W20: point at where each agent's onboarding text/token can be produced -- never print
            // the token itself, or the built prompt (which embeds it), to this log. McpAccessPrompt
            // .build(endpoint, agent.label(), agent.token()) is the ready-to-call generator; wiring
            // an actual "Copy access prompt" clipboard button to it is NumenScreen.java's job (a
            // different wave's file set — see this wave's followUps).
            String endpoint = "http://" + cfg.host() + ":" + cfg.port() + "/mcp";
            for (McpConfig.Agent agent : cfg.agents()) {
                Constants.LOG.info("[numen-mcp] agent '{}' (id: {}) configured for {} -- its onboarding "
                        + "prompt (endpoint, auth header, ready-to-paste client config, operating contract) "
                        + "can be generated with McpAccessPrompt.build(); its token lives in {}, never in "
                        + "this log", agent.label(), agent.id(), endpoint, configDir.resolve("numen").resolve("mcp_server.json"));
            }
        } catch (Exception ex) {
            Constants.LOG.error("[numen-mcp] failed to start MCP server on {}:{} — {}",
                    cfg.host(), cfg.port(), ex.toString());
            server = null;
        }
    }
}
