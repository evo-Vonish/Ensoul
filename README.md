# Ensoul (有灵)

> **They were never NPCs.**

**Ensoul** makes Minecraft bodies real and brains pluggable.

A companion is not a script — it is a body with a genuine agent loop behind its
eyes: it perceives the world as region snapshots, reasons, calls tools to act,
and remembers what happened in an **append-only memory**, while low-level
**survival reflexes** keep it alive underneath the slow, deliberate brain. It
walks, mines, fights, builds, escapes lava, follows you, and talks.

And the brain is a socket, not a fixture. A body can be driven by its **built-in
LLM brain** — or *ensouled* by an **external desktop agent**: Claude Code, Codex,
Kimi Code, anything that speaks MCP can take a body, live in the world through
it, and hand it back. That is the project's direction: not one AI pet, but a
world where many minds — local and external — inhabit bodies side by side,
form alliances, trade, and compete.

**One Brain, One Body.** At any moment exactly one brain may control a companion
body, and the *server* is the authority on that state — enforced with control
leases and per-call tokens, not client-side flags. Takeover, release, timeout,
and disconnect are all explicit, so two brains can never fight over one body,
even with several external agents driving different companions at once.

---

## The proving ground

The first milestone for all of this is an **AI competition**: desktop agents
paired into alliances, each ensouling a companion, racing to reach the Nether
and bring back a blaze rod. Everything below — the control model, information
parity for external agents, the coordinator — exists to make that race fair,
legible, and worth filming.

---

## Repository layout

A history-preserving monorepo (every upstream commit retained per subtree).
Each module is a self-contained [MultiLoader](https://github.com/jaredlll08/MultiLoader-Template)
project (`common` + `fabric` + `neoforge`). Targets **Minecraft 26.1.2**, **JDK 25**.

| Path       | What it is |
|------------|------------|
| `engine/`  | The agent engine (`numen_api`): agent loop, LLM provider adapters, streaming transport with retry/idle-watchdog, tool dispatch, the **control authority** (`ControlRegistry`, control leases, state sync), companion entities, skins, and the in-game GUI toolkit. Published to a local Maven repo; consumed by the other two. |
| `mod/`     | The tool pack (`numen-core`): world tools and tasks, **L0 survival reflexes** (drowning escape, fire/lava retreat, creeper avoidance), **motor skills** (`pillar_up` / `bridge_to` / `escape_to_surface`, fault-tolerant placement), pathing, region-aware cognition, and the server tick dispatcher that gates every action on control state. |
| `mcp/`     | The **MCP bridge**: exposes companions to external agents with per-agent identity and token auth. The cluster **coordinator** (broadcast / whisper / locked trade with append-only inboxes) lives on `feature/cluster-coordinator`. |

## Getting started

```bash
# 1. Publish the engine to a local Maven repository (any writable path).
cd engine
./gradlew publish -Plocal_maven_url=file:///path/to/local-maven

# 2. Build / run the mod against it (same path!).
cd ../mod
./gradlew build      -Plocal_maven_url=file:///path/to/local-maven
./gradlew :fabric:runClient -Plocal_maven_url=file:///path/to/local-maven

# 3. The MCP bridge.
cd ../mcp
./gradlew build      -Plocal_maven_url=file:///path/to/local-maven
```

Build order matters: the engine must be published before `mod/` or `mcp/` can
see engine changes. (A single multi-project build is on the roadmap.)

In game: **G** opens the companion roster. Summon a companion by name — names
are globally unique, case-insensitive, max 16 characters, and the server tells
you why when one is rejected.

**Custom skins:** drop a 64×64 PNG at `config/numen_api/skins/<lowercase-name>.png`
in the game directory (`default.png` is the catch-all). For the dev client that
is `mod/fabric/runs/client/config/numen_api/skins/`.

## External agents over MCP

The bridge speaks JSON-RPC (MCP) and is configured in `config/numen/mcp_server.json`:

- **Per-agent identity** — each connecting agent (Claude Code, Codex, Kimi
  Code, …) gets its own id, label, and token, so controllers are
  distinguishable and independently revocable.
- **Auth policy** — loopback binds are frictionless; any network-visible bind
  *requires* token auth. A blank token never means open access on a network.
- **Control leases** — an agent `acquire`s a companion (refused, with the
  holder's name, if another brain holds it), drives it with the same tools the
  built-in brain uses, and `release`s it. Leases renew while authorized work
  runs, expire on silence, and are force-released when the owner logs out or
  clicks Release. While a body is externally held, its built-in brain does not
  think, its idle autonomy stands down, and the owner's panel shows who is
  driving.
- **Survival reflexes stay on for everyone** (configurable): a body mid-thought
  should not drown. Reflex takeovers are attributed and reported, never silent.

Full information parity — the external agent receiving everything the built-in
brain receives (world cognition, urgent events, lifecycle, notices, usage) as
push rather than polling — is in active development (`wip/mcp-parity-w11-w14`).

## Design principles

- **Observation creates immutable knowledge events.** Revisits append
  corrections; nothing rewrites prior perception. The full contract is in
  [`docs/numen-context-design-v1.md`](docs/numen-context-design-v1.md) —
  append-only world cognition, stable prompt prefix, compaction as the only
  legal rewrite boundary, and a forgetting protocol that keeps memory but
  covenants silence.
- **One brain, one body; the server decides.** Control is derived state
  (lease + baseline), synced to clients by full replacement so a dropped
  packet can never strand a body.
- **Take upstream's good ideas, at our own speed.** Ensoul tracks its upstream
  for ideas worth adopting and diverges where the vision differs — most
  notably: upstream moved to a single global external-brain switch; Ensoul
  keeps per-body control precisely because many simultaneous external agents
  are the point.

## Upstream & license

Ensoul is a fork of the **Numen** project by **Dwinovo**, welded from its three
repositories with full commit history preserved
([`numen-api`](https://github.com/Dwinovo/numen-api) ·
[`minecraft-numen`](https://github.com/Dwinovo/minecraft-numen) ·
[`numen-mcp`](https://github.com/Dwinovo/numen-mcp)).

- **Source code — [LGPL-3.0](engine/LICENSE).** Distributed forks stay open
  under the same license.
- **Public integration API — [MIT](engine/LICENSE-API).** The surface that
  compat modules and MCP bridges code against stays permissive.
- **Art & assets — [LGPL-3.0](engine/LICENSE-ASSETS).** All upstream art (All
  Rights Reserved) has been removed; every asset here is original placeholder
  work for this fork.

The upstream names **"Numen"** / **"言出法随"** and branding are reserved by
their authors and are not used to identify this project — hence **Ensoul (有灵)**.

## Roadmap

- **Full MCP information parity** — SSE push channel, resources & prompts,
  event-stream tee with sequence envelopes intact (in flight).
- **External-control console** — the chat panel becomes a driver console while
  a body is externally held: controller identity, lease countdown, activity
  stream, stop/release.
- **Creation dialog** — name validation UI, skin picker, brain baseline
  (built-in vs external-only), game mode.
- **Agent society** — land the cluster coordinator (broadcast / whisper /
  locked trade) into main; companions that perceive and act on *each other*.
- **One Gradle multi-project build** — retire the local-Maven publish dance.
- **The race.** Nether. Blaze rod. Cameras rolling.

---

## 关于 Ensoul(有灵)

Ensoul(有灵)让 Minecraft 的身体成为真的身体,让大脑成为可插拔的东西。同伴不是
脚本:它有完整的 agent 循环——把世界读成区域快照、推理、调用工具行动、以**只追加
记忆**记下发生的一切;底层**生存反射**(溺水逃生、火/岩浆规避、苦力怕回避)在缓慢
慎重的大脑之下把身体稳住。

而大脑是插座,不是固定件:身体可以由**内置 LLM 大脑**驱动,也可以被**外部桌面
Agent**"附体"——Claude Code、Codex、Kimi Code,任何会说 MCP 的都能取得一具身体、
通过它活在世界里、再把它交还。**一脑一身**:任一时刻恰好一个大脑控制一具身体,
以**服务器**为唯一权威(控制租约 + 逐调用令牌),多个外部 Agent 同时驱动不同身体
也绝不打架。

第一个里程碑是一场 **AI 竞赛**:桌面 Agent 结成同盟、各自附体,比赛谁先抵达下界
拿到烈焰棒。本仓库源自 Dwinovo 的 **Numen** 三仓库,保全全部提交历史;上游名称与
美术已全部移除,源码 LGPL-3.0,对接 API 为 MIT。**They were never NPCs.**
