# Ensoul (有灵)

> **They were never NPCs.**

**Ensoul** turns Minecraft mobs into agent-architecture-driven living companions:
LLM brains wired into real bodies — with **reflexes**, **motor skills**,
**attention**, and **append-only memory**. Instead of a scripted dialogue tree, a
companion runs a genuine agent loop: it perceives the world as a region snapshot,
reasons with a large language model, calls tools to act, and remembers what
happened — while low-level survival reflexes keep the body alive underneath the
slow, deliberate brain.

The result is a creature that walks, mines, fights, eats, escapes lava, follows
you, and talks — because something is *home* behind its eyes. They were never NPCs.

---

## Repository layout

This is a history-preserving monorepo welded from three upstream repositories.
Every commit from each source project is retained under its subtree.

| Path       | What it is | Upstream |
|------------|------------|----------|
| `engine/`  | The **agent-architecture engine** (`numen_api`): the LLM agent loop, provider adapters, streaming, the tool-dispatch layer, the actuator seam that lets an external brain take over a body, and the in-game GUI toolkit (`UiTheme`, `NumenScreen`, HUD toasts). Published to a local Maven repo and consumed by the mod. | [`Dwinovo/numen-api`](https://github.com/Dwinovo/numen-api) |
| `mod/`     | The **Minecraft mod** itself: companion entities and registry, **L0 survival reflexes** (drowning escape, fire/lava retreat, auto-eat), **motor skills** (place-block fault tolerance, `pillar_up` / `bridge_to` / `escape_to_surface`), region-aware cognition and navigation, and a benchmark harness for scoring trajectories. | [`Dwinovo/minecraft-numen`](https://github.com/Dwinovo/minecraft-numen) |
| `mcp/`     | The **MCP bridge**: exposes the companion/agent surface over the Model Context Protocol, plus an agent-skill package, so external MCP clients can drive or observe the in-world agents. | [`Dwinovo/numen-mcp`](https://github.com/Dwinovo/numen-mcp) |

Each module is a self-contained [MultiLoader-Template](https://github.com/jaredlll08/MultiLoader-Template)
project (`common` + `fabric` + `neoforge`) with its own Gradle wrapper.

---

## Building & getting started

Requirements: **JDK 25** (the game targets a modern toolchain) and the bundled
Gradle wrapper in each module. The mod depends on the engine as a published
artifact, so **build order matters**: publish the engine to a local Maven repo
first, then run the mod against it.

```bash
# 1. Publish the engine (numen_api) to a local Maven repository.
cd engine
./gradlew publish -Plocal_maven_url=file:///F:/Projects/numen/numen-maven

# 2. Launch the mod client, resolving the engine from that same local repo.
cd ../mod
./gradlew runClient -Plocal_maven_url=file:///F:/Projects/numen/numen-maven

# 3. (optional) The MCP bridge builds independently.
cd ../mcp
./gradlew build
```

Point `local_maven_url` at any writable directory you like — it only needs to be
the *same* path for the publish step and the consume step. A single **Gradle
composite build** that wires the three modules together (no manual publish step)
is on the roadmap.

---

## Upstream & license

Ensoul is a fork of the **Numen** project by **Dwinovo**, welded from its three
repositories with full commit history preserved:

- Engine — [`Dwinovo/numen-api`](https://github.com/Dwinovo/numen-api)
- Mod — [`Dwinovo/minecraft-numen`](https://github.com/Dwinovo/minecraft-numen)
- MCP bridge — [`Dwinovo/numen-mcp`](https://github.com/Dwinovo/numen-mcp)

Licensing follows the upstream split (modeled on AE2):

- **Source code — [LGPL-3.0](engine/LICENSE).** Forks you distribute must stay open under
  the same license.
- **Public integration API — [MIT](engine/LICENSE-API).** The surface that
  compatibility modules and MCP bridges code against stays permissive, so anyone
  can build mod-compat freely.
- **Art & assets — [LGPL-3.0](engine/LICENSE-ASSETS).** The upstream project's
  original artwork and branding were All Rights Reserved. **All upstream art has
  been removed**; every asset that ships here is original placeholder work made for
  this fork and is released under the same LGPL-3.0 as the code.

The upstream names **"Numen"** and **"言出法随"** and the upstream branding are
reserved by their authors and are **not** used to identify this project — hence the
rename to **Ensoul (有灵)**.

---

## Roadmap

- **Gradle composite build** — wire `engine` / `mod` / `mcp` into one build so the
  engine no longer has to be published to a local Maven repo by hand.
- **Creative flight** — a motor-skill wave for creative-mode flight, so companions
  can move in three dimensions the way a player can.
- **NPC society** — companions that perceive, remember, and act on *each other*:
  the beginnings of an in-world agent society rather than a set of isolated pets.

---

## 关于 Ensoul（有灵）

Ensoul（有灵）把 Minecraft 里的生物变成由 **agent 架构**驱动的活体伙伴：真正的
LLM 大脑装进真实的身体里，配上**反射**、**运动技能**、**注意力**与**只追加记忆**。
它不是对话树,而是一个完整的 agent 循环——把世界读成区域快照,用大模型推理,调用
工具去行动,再把发生的事记下来;底层的生存反射(溺水逃生、火/岩浆规避、自动进食)
在缓慢慎重的大脑之下把身体稳住。本仓库由上游 Dwinovo 的 **Numen** 三个仓库
(numen-api / minecraft-numen / numen-mcp)保全全部提交历史焊接而成;上游名称与美术
(保留所有权利)已全部移除并更名为 **Ensoul**,源码沿用 LGPL-3.0、对接 API 为 MIT。
**They were never NPCs.**
