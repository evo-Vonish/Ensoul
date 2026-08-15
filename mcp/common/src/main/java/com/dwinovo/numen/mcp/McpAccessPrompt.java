package com.dwinovo.numen.mcp;

/**
 * W20 — the copyable onboarding text that teaches a connecting desktop agent (Claude Desktop,
 * Claude Code, Cursor, Cline, Windsurf, ...) how to configure ITS OWN MCP client against this
 * server, without a human hand-editing JSON. {@link #build} is pure string assembly — no I/O, no
 * Minecraft state — so it is trivially callable from wherever the UI ends up wiring the "Copy
 * access prompt" button (see the class doc's final section for why that wiring is NOT done here).
 *
 * <h2>Why English, always</h2>
 * The reader may be any model behind any client, and English instruction-following is the most
 * consistent across all of them — so the body is deliberately English even when the owner's own
 * client is set to another language, with an explicit closing line asking the assistant to reply
 * to the owner in the owner's own language once it has followed these steps.
 *
 * <h2>Per-agent, never shared</h2>
 * {@link #build} takes one token, not the whole {@link McpConfig}: call it once per {@link
 * McpConfig.Agent} (see {@code McpConfig#agents()}) so each of the competing desktop agents in
 * the filmed-race format gets ITS OWN prompt carrying ITS OWN credential — a shared prompt would
 * either leak one agent's token to the others or force them all to fight over one identity, which
 * defeats the entire point of per-agent auth (Wave 1's {@code w1-mcp-auth}).
 *
 * <h2>Never logged, never rendered on screen</h2>
 * The returned string embeds a plaintext bearer token. Every caller of this method — present or
 * future — must treat the result the same way a password manager treats a password: clipboard
 * only, never a log line, never an on-screen text widget. See {@link NumenMcp}'s startup log for
 * the pattern this follows (point at where the token lives; never print the token itself).
 *
 * <h2>What is NOT wired here</h2>
 * The design's "Copy access prompt" button belongs in the Settings tab of {@code
 * NumenScreen.java}, which is outside this wave's file set (a different wave/agent owns the UI).
 * This class is the complete, ready-to-call string-building half; wiring a button that calls
 * {@link #build} and puts the result on the system clipboard (never in a visible widget — the UI
 * should show only a masked form, first 8 characters plus bullets) is left as a followUp for
 * whoever owns that file.
 */
final class McpAccessPrompt {

    private McpAccessPrompt() {}

    /**
     * @param endpoint   the literal MCP HTTP endpoint, e.g. {@code http://127.0.0.1:8765/mcp}
     * @param agentLabel the {@link McpConfig.Agent#label()} this prompt is being generated for —
     *                   folded into the generated config's server-name key so several agents'
     *                   configs never collide when merged into one {@code mcpServers} block
     * @param token      this agent's bearer token, or blank for a loopback bind with no auth
     */
    static String build(String endpoint, String agentLabel, String token) {
        boolean hasToken = token != null && !token.isBlank();
        String serverKey = "numen-" + slug(agentLabel);

        StringBuilder sb = new StringBuilder(4096);

        sb.append("You are being connected to Numen, an AI-companion mod for Minecraft. Through an MCP ")
          .append("(Model Context Protocol) server it exposes, you can take control of a companion's body ")
          .append("and play the game as it: perceive the world, move, mine, build, craft, fight. You are ")
          .append("the brain; the companion is your hands and eyes, and its own built-in AI steps aside ")
          .append("while you drive.\n\n");

        sb.append("Endpoint: ").append(endpoint).append('\n');
        if (hasToken) {
            sb.append("Auth: every request must carry the header `Authorization: Bearer ").append(token)
              .append("`. Keep this token private -- it grants remote control of the owner's companions.\n\n");
        } else {
            sb.append("Auth: none configured (loopback-only bind) -- no Authorization header is required.\n\n");
        }

        sb.append("=== Step 1: figure out which MCP client you are running in ===\n")
          .append("Identify your own client and its MCP config location before editing anything:\n")
          .append("  - Claude Desktop: claude_desktop_config.json (Settings > Developer > Edit Config)\n")
          .append("  - Claude Code: .mcp.json in the project root, or `claude mcp add` on the command line\n")
          .append("  - Cursor: .cursor/mcp.json in the project root, or Cursor Settings > MCP\n")
          .append("  - Cline: cline_mcp_settings.json via the Cline extension's MCP Servers panel\n")
          .append("  - Windsurf: ~/.codeium/windsurf/mcp_config.json\n")
          .append("If you can't tell, ask me.\n\n");

        sb.append("=== Step 2: add this server ===\n")
          .append("This server speaks Streamable HTTP; if your client cannot dial an HTTP MCP server ")
          .append("directly, bridge it with `mcp-remote` (needs Node.js/npx on this machine). Paste the ")
          .append("following into your config's `mcpServers` object:\n\n")
          .append("IMPORTANT: MERGE this into any existing `mcpServers` block -- do NOT overwrite it. ")
          .append("Other agents (Claude Code, Codex, Kimi Code, ...) may already have their own entries ")
          .append("configured on this same machine; replacing the whole block would delete their access.\n\n");

        sb.append("{\n")
          .append("  \"mcpServers\": {\n")
          .append("    \"").append(serverKey).append("\": {\n")
          .append("      \"command\": \"npx\",\n")
          .append("      \"args\": [\n")
          .append("        \"-y\",\n")
          .append("        \"mcp-remote\",\n")
          .append("        \"").append(endpoint).append('"');
        if (hasToken) {
            sb.append(",\n")
              .append("        \"--header\",\n")
              .append("        \"Authorization: Bearer ").append(token).append('"');
        }
        sb.append("\n      ]\n")
          .append("    }\n")
          .append("  }\n")
          .append("}\n\n");

        sb.append("=== Step 3: verify ===\n")
          .append("1. Call list_companions -- you should see the owner's live companions.\n")
          .append("2. Call acquire_companion on one of them -- its built-in brain pauses and you take over.\n")
          .append("3. Call list_companions again -- that companion's state should now show you as the controller.\n\n");

        sb.append("=== Operating contract ===\n")
          .append("- acquire_companion is REQUIRED before any action tool works on a companion.\n")
          .append("- A second agent's acquire on a body you already hold is REFUSED, not stolen -- it will ")
          .append("be told your label as the current holder, and you'll be told the same if you try to take ")
          .append("one someone else holds.\n")
          .append("- Your lease expires after a few minutes of silence. Call heartbeat, or just keep making ")
          .append("calls, to keep it alive; if you go idle too long the built-in brain (or another agent) ")
          .append("may resume the body.\n")
          .append("- Lease/roster/death-respawn changes arrive as notifications if you open a GET /mcp ")
          .append("Streamable-HTTP stream (send back the Mcp-Session-Id you were given at initialize). ")
          .append("World-PERCEPTION events (being attacked, low HP, items spotted) are NOT pushed in this ")
          .append("build -- poll get_self_status / scan_nearby_entities between actions instead.\n")
          .append("- Survival reflexes (drowning, fire, falling) can seize the body out from under you in an ")
          .append("emergency even while you hold the lease -- you will be told when this happens; resume ")
          .append("driving once the emergency note clears.\n\n");

        sb.append("Reply to the person you're working with in THEIR language, not necessarily English, once ")
          .append("you have worked through the steps above.");

        return sb.toString();
    }

    /** Lowercase, non-alphanumeric runs collapsed to a single hyphen, trimmed — turns a free-text
     *  agent label like "Claude Code · 3f2a" into a safe JSON-object-key / server-name fragment. */
    private static String slug(String label) {
        if (label == null || label.isBlank()) return "agent";
        String s = label.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        s = s.replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "agent" : s;
    }
}
