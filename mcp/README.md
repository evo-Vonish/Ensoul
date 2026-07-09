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

MIT licensed.

---

## 中文简介

把你的 [Numen](https://github.com/Dwinovo/minecraft-numen) 同伴变成外部 AI 能直接操控的工具。Numen MCP 在你的游戏客户端里跑一个小型 MCP 服务器,让 Claude 这样的智能体列出你的同伴、接管其中一个、直接调用它的工具——**Claude 当大脑,同伴当手和眼**。

和普通 Numen 同伴的区别:普通同伴用自己内置的大模型思考;Numen MCP 让外部大脑绕过那个 LLM,Claude 通过感知工具读世界、自己做每一个决策。这个模式**不需要 Numen 自己的 API Key**。每次调用指定一个同伴,每具身体独立跑任务,所以一个 Claude 能**并行指挥一支同伴舰队**。暴露的工具就是内置脑子用的那批(挖、走、放、合成),凭空造物做不到。

需要 **Numen 0.0.4+**(内含带 `NumenActuator` 的引擎),客户端安装。首次启动写入 `config/numen/mcp_server.json`,连接方式见上方。多人服照常——纯客户端,走 Numen 现成的客户端→服务端协议;服务器只需装 Numen、会对每个动作做归属校验,你只能驱动自己的同伴。

MIT 协议。
