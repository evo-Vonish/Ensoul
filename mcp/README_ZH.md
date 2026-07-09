# Numen MCP

把你的 [Numen](https://github.com/Dwinovo/minecraft-numen) 同伴变成外部 AI 能直接操控的工具。Numen MCP 在你的游戏客户端里跑一个小型 [Model Context Protocol](https://modelcontextprotocol.io) 服务器,让 Claude 这样的智能体列出你的同伴、接管其中一个、直接调用它的工具——**Claude 当大脑,同伴当手和眼**。

多加载器(Fabric + NeoForge),Minecraft 1.21.1。

## 与普通 Numen 同伴的区别

普通 Numen 同伴用自己内置的 LLM 思考。Numen MCP 让**外部**大脑完全绕过那个 LLM:

- **Claude 就是玩家。** 它通过感知工具(`get_self_status`、`scan_blocks` …)读取世界,自己决定每一个动作。
- **这个模式无需 Numen API Key**——Claude 负责推理,同伴只负责执行工具。
- **并行舰队。** 每次调用都指定一个同伴,每具身体独立跑自己的任务,一个智能体可以同时驱动多个同伴。
- **合乎生存规则。** 暴露出来的工具就是内置大脑用的那批——挖矿、移动、放置、合成。凭空造物做不到。

## 环境要求

- **[Numen](https://github.com/Dwinovo/minecraft-numen) 0.0.4+**(内含带 `NumenActuator` 的 numen-api 引擎)
- 客户端安装——同伴和它们的工具注册表都活在拥有者的游戏客户端里
- 一个 MCP 客户端:Claude Code(原生 HTTP)或 Claude Desktop(通过 `mcp-remote`)

## 配置步骤

1. 在客户端里安装 Numen + Numen MCP。启动一次,它会写入 `config/numen/mcp_server.json`。开始监听时日志会打印 `MCP server up on http://127.0.0.1:8765/mcp`。

2. 把你的 MCP 客户端指向它:
   - **Claude Code** —— 在项目里的 `.mcp.json`:
     ```json
     { "mcpServers": { "numen": { "type": "http", "url": "http://127.0.0.1:8765/mcp" } } }
     ```
   - **Claude Desktop** —— `claude_desktop_config.json`(需要 Node/npx):
     ```json
     { "mcpServers": { "numen": { "command": "npx", "args": ["mcp-remote", "http://127.0.0.1:8765/mcp"] } } }
     ```

3. 在游戏里召唤一个同伴,然后在你的智能体里:`list_companions` → `acquire_companion` → 开始驱动。MCP 服务器只在游戏运行时监听。

## 工具

| 工具 | 作用 |
|---|---|
| `list_companions` | 列出你当前在线的同伴(名字 + id) |
| `acquire_companion` | 接管——暂停内置大脑,释放身体 |
| `release_companion` | 把同伴交还给它的内置大脑 |
| *(引擎工具)* | 每一个 Numen 身体/感知工具(`get_self_status`、`scan_blocks`、`auto_mine`、`move_to`、`place_block` …),每个都接收一个 `companion` 参数 |

## 多人联机

在远程服务器上照常工作。Numen MCP 纯客户端,通过 Numen 现成的客户端→服务端协议驱动——用的就是内置大脑用的那批数据包。服务器需要装 Numen(它本来就得装,同伴才存在),但**不需要**装 Numen MCP。服务器会对每个动作做归属校验,你只能驱动自己拥有的同伴。

## 配置参考 —— `config/numen/mcp_server.json`

- `enabled` —— 总开关。
- `host` / `port` —— HTTP 端点绑定的地址(默认走本地回环)。
- `token` —— 可选的 bearer token;设置后请求必须带上它(`Authorization: Bearer <token>` 或 `?token=`)。
- `call_timeout_seconds` —— 一次 `tools/call` 等待身体动作多久后报超时。
- `hidden_tools` —— 不向外部智能体暴露的引擎工具(智能体内部的记账用途)。

## 状态

早期阶段。动作工具目前是**阻塞式**的(一次 `tools/call` 会一直挂住,直到身体完成任务);非阻塞的 start/poll 模型和一个 Groovy 技能编写层已在规划中。

## 生态

- **[Numen](https://github.com/Dwinovo/minecraft-numen)** —— 本体 mod。Minecraft 的 AI 同伴。
- **[numen-api](https://github.com/Dwinovo/numen-api)** —— 引擎。Numen MCP 通过它的 `NumenActuator` 驱动同伴。
- **[numen-qq-mcp](https://github.com/Dwinovo/numen-qq-mcp)** —— 姊妹插件。Numen 的 QQ 桥接。
- **[numen-maven](https://github.com/Dwinovo/numen-maven)** —— 托管 numen-api 的 Maven 仓库。

MIT 协议。
