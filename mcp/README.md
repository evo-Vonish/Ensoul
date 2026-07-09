# Numen MCP

Turn your [Numen](https://github.com/Dwinovo/minecraft-numen) companions into tools an external AI can drive. Numen MCP runs a small [Model Context Protocol](https://modelcontextprotocol.io) server inside your game client, so an agent like **Claude** can list your companions, take control of one, and call its tools directly — Claude becomes the brain, the companion is its hands and eyes.

Multi-loader (Fabric + NeoForge), Minecraft 1.21.1.

## How it differs from a normal Numen companion

A Numen companion normally thinks with its own built-in LLM. Numen MCP lets an **outside** brain skip that LLM entirely:

- **Claude is the player.** It reads the world through perception tools (`get_self_status`, `scan_blocks`, …) and decides every action itself.
- **No Numen API key needed** for this mode — Claude does the reasoning; the companion just executes tools.
- **Parallel fleets.** Every call is addressed to one companion, and each body runs its tasks independently, so one agent can drive several companions at once.
- **Survival-legitimate.** The exposed tools are the same ones the built-in brain uses — mine, move, place, craft. Nothing conjures items from nothing.

## Requirements

- **[Numen](https://github.com/Dwinovo/minecraft-numen) 0.0.4+** (bundles the numen-api engine with `NumenActuator`)
- Client-side — companions and their tool registry live in the owner's game client
- An MCP client: Claude Code (native HTTP) or Claude Desktop (via `mcp-remote`)

## Setup

1. Install Numen + Numen MCP in your client. Launch once; it writes `config/numen/mcp_server.json`. The log prints `MCP server up on http://127.0.0.1:8765/mcp` when it's listening.

2. Point your MCP client at it:
   - **Claude Code** — `.mcp.json` in your project:
     ```json
     { "mcpServers": { "numen": { "type": "http", "url": "http://127.0.0.1:8765/mcp" } } }
     ```
   - **Claude Desktop** — `claude_desktop_config.json` (needs Node/npx):
     ```json
     { "mcpServers": { "numen": { "command": "npx", "args": ["mcp-remote", "http://127.0.0.1:8765/mcp"] } } }
     ```

3. Summon a companion in-game, then in your agent: `list_companions` → `acquire_companion` → drive it. The MCP server only listens while the game is running.

## Tools

| Tool | What it does |
|---|---|
| `list_companions` | List your live companions (name + id) |
| `acquire_companion` | Take control — pauses the built-in brain, frees the body |
| `release_companion` | Hand the companion back to its built-in brain |
| *(engine tools)* | Every Numen body/perception tool (`get_self_status`, `scan_blocks`, `auto_mine`, `move_to`, `place_block`, …), each taking a `companion` argument |

## Multiplayer

Works on remote servers. Numen MCP is client-only and drives through Numen's existing client→server protocol — the same packets the built-in brain uses. The server needs Numen installed (as it already does for companions to exist); it does **not** need Numen MCP. The server owner-checks every action, so you can only drive companions you own.

## Config reference — `config/numen/mcp_server.json`

- `enabled` — master switch.
- `host` / `port` — where the HTTP endpoint binds (loopback by default).
- `token` — optional bearer token; when set, requests must present it (`Authorization: Bearer <token>` or `?token=`).
- `call_timeout_seconds` — how long a `tools/call` waits for a body action before reporting a timeout.
- `hidden_tools` — engine tools NOT exposed to the external agent (agent-internal bookkeeping).

## Status

Early. Action tools are currently **blocking** (a `tools/call` holds until the body finishes the task); a non-blocking start/poll model and a Groovy skill-writing layer are planned.

## Ecosystem

**Numen** ([minecraft-numen](https://github.com/Dwinovo/minecraft-numen)) is the mod — the AI companion. It runs on the **[numen-api](https://github.com/Dwinovo/numen-api)** engine (published through **[numen-maven](https://github.com/Dwinovo/numen-maven)**), which exposes a small public API. Two things build on it:

**Extend a companion** — its own brain stays in charge:
- **Bridges** carry an outside channel into a companion: a message arrives, and the companion decides what to do. Built on `NumenGateway`. → **[numen-qq-bridge](https://github.com/Dwinovo/numen-qq-bridge)** (QQ), with more to come.
- **Skills** teach a companion how to behave — markdown loaded into its context. Bundled with Numen, or community-written.

**Expose Numen** — hand the controls to an outside brain:
- **[numen-mcp](https://github.com/Dwinovo/numen-mcp)** is a Model Context Protocol server: any external agent (like Claude) drives companions directly. Built on `NumenActuator`. *(this repo)*

MIT licensed.
