# ICE-MCP v1 — 交错式上下文执行,跨越 harness 边界

**The external-agent derivation of ICE. Design doc, implementable.**

> 契约:
> **Ensoul is the executor and the observed world. The agent's harness owns the context.**
> **Dispatch returns a placeholder; the journal returns the truth; waiting is a tool.**

姊妹文档:《交错式上下文执行(ICE)》· 《numen 上下文机制设计 v1》(CESP/CIAP)

---

## 0. Why rebuild rather than extend

The current `McpServer` is 500 lines of stateless request/response. Its structure —
not its bugs — is what blocks ICE:

| Current | Consequence |
|---|---|
| `com.sun.net.httpserver`, **POST `/mcp` only** | No server→client channel exists. Push is not "unimplemented", it is unrepresentable. |
| **Four methods**: `initialize`, `ping`, `tools/list`, `tools/call` | No resources, no prompts, no notifications, no logging. Tool parity was mistaken for parity. |
| **No session object** | The agent is re-resolved from a bearer token per request. "Who is connected", "what does this agent hold", "did it disconnect" have no representation. |
| `leaseTokens : Map<UUID companion, String>` — **global** | Two agents holding two companions share one map. Multi-agent was never modelled. |
| `tools/call` blocks until the task finishes (≤300 s) | Synchronous blocking in async clothing — ICE §1's 三重浪费, exactly. |

Nothing above is fixable by addition. `McpServer.java` is deleted. `McpConfig` (per-agent
identity, minted tokens, loopback/LAN policy) is **kept** — it landed correctly in `7673309`.
`NumenMcp` stays as the loader-facing shell.

---

## 1. The boundary theorem

ICE §5.1 says the linear token band admits exactly one writer. For the built-in brain we own
that band, so we get the full machine: reorder buffer, watermark, in-order retirement.

**For an external agent we do not own it.** Claude Code's context belongs to Claude Code's
harness. We cannot reorder its tail, cannot retire, cannot merge a result back into a
placeholder's byte position.

Therefore the derivation across the boundary:

```text
ICE component            built-in brain        external agent
──────────────────────────────────────────────────────────────────────
placeholder protocol     yes                   YES  — unchanged, §4.2
event envelope + seq     yes                   YES  — the journal, §3
停召 / dependency stall  yes                   YES  — becomes await_events, §5
causal red line          yes                   YES  — free: we never re-emit
short-track merge        yes                   NO   — their tape, not ours
watermark / retirement   yes                   NO   — their compaction
```

**Every external tool call is long-track (ICE §4.3).** Reference-append was always the mode
for slow tasks, and everything crossing a harness boundary is slow by ICE's standards. The
loss of 短轨整洁史 is not a compromise — it is the correct branch of a decision the paper
already made.

---

## 2. Architecture

```text
                    ┌──────────────────── owner's game client ────────────────────┐
 Claude Code ──┐    │                                                             │
 Codex ────────┼─HTTP┤  McpHttpServer ── McpSessions ── McpSession(agent, leases,  │
 Kimi Code ────┘    │       │                              cursor, sseSink)        │
                    │       │                                    ▲                 │
                    │       ├── dispatch ── tools ── ActuatorTools ─► NumenActuator │
                    │       │                    ├─ ControlTools ──► ControlRegistry│
                    │       │                    └─ LifecycleTools ──┐   (server)   │
                    │       │                                        │              │
                    │       └── resources / prompts                  ▼              │
                    │                                          McpJournal            │
                    │                                    (ring, seq, cursor, gap)    │
                    │                                          ▲  ▲  ▲              │
                    │        EntityAgentLoop events ───────────┘  │  │              │
                    │        TaskResultPayload ───────────────────┘  │              │
                    │        ControlStatePayload ────────────────────┘              │
                    └─────────────────────────────────────────────────────────────┘
```

The MCP server runs **inside the owner's client** (unchanged — `NumenActuator`,
`AgentLoopRegistry`, `ClientControl` are all client-side). This is correct and load-bearing:
the client is where world events, task results and control state already converge. The
journal has no new information to gather; it only has to *tee* what already arrives.

### File layout

```
mcp/common/src/main/java/com/dwinovo/numen/mcp/
  NumenMcp.java                 KEEP   loader shell
  McpConfig.java                KEEP   per-agent identity, token policy
  McpServer.java                DELETE
  transport/
    McpHttpServer.java          NEW    Streamable HTTP: POST/GET/DELETE
    McpSession.java             NEW    identity, leases, cursor, sink
    McpSessions.java            NEW    registry + idle reaper
    SseSink.java                NEW    one SSE writer, backpressure-aware
  protocol/
    JsonRpc.java                NEW    request/response/error/notification
    Dispatch.java               NEW    method table
  journal/
    JournalEvent.java           NEW    CESP envelope + taskId
    McpJournal.java             NEW    ring buffer, seq, cursor, gap marker
    JournalSources.java         NEW    the tee wiring (§3.3)
  tools/
    ActuatorTools.java          NEW    ToolRegistry bridge, placeholder-returning
    ControlTools.java           NEW    acquire/release/list — session-scoped
    LifecycleTools.java         NEW    await_events / task_status / cancel_task
  resources/
    McpResources.java           NEW    persona, protocol, control state, usage
    McpPrompts.java             NEW    onboarding prompt
```

The `wip/mcp-parity-w11-w14` branch is **not merged**. Its `McpSession`/`McpResources` are
read as reference for shape only; they call APIs that were never written and contain at
least one invented Minecraft call (`ResourceKey.location()` → `.identifier()`).

---

## 3. The journal — the thing that was actually missing

The user's stated pain — *"it is difficult for the Agent to continuously receive information
from the game"* — is not a transport problem. It is the absence of an addressable event log.

### 3.1 Envelope

`JournalEvent` is the CESP §3 envelope plus ICE §4.6's task tag:

```json
{
  "seq": 1042,
  "schemaVersion": 1,
  "gameTime": 184220,
  "type": "TASK_COMPLETED",
  "provenance": "observed",
  "companion": "b09de2f9-…",
  "taskId": "t-7",
  "body": { "…type-specific…" }
}
```

`seq` is a single monotonic counter across the whole journal (not per companion) — one
totally ordered stream is what makes a cursor meaningful. Ordering is the **delivery** order,
which per CESP is itself an observation; we never reorder to look tidy (§4.5 因果红线 holds
for free, because we only ever append).

### 3.2 Ring, cursor, gap

`McpJournal` is a bounded ring (default 2048 events). A session holds a cursor.

**If a cursor falls off the back of the ring, we do not silently truncate.** The next read
returns a `GAP` event first:

```json
{"seq": 900, "type": "GAP", "body": {"missed": 137, "oldestAvailable": 901,
 "guidance": "Events were dropped. State you rely on may have changed; re-read
 numen://companion/{uuid}/status before acting on stale assumptions."}}
```

Honesty over silence — the same principle as CESP's 冲突保留来源. An agent that missed
events must know it missed them.

### 3.3 Sources (the tee)

| Source | Produces |
|---|---|
| `EntityAgentLoop` event queue | `PERCEPTION` (urgent + ambient), `REGION_SNAPSHOT`, `REGION_DIFF`, `LANDMARK_EVENT`, `SYSTEM_NOTICE` |
| `TaskResultPayload` handler | `TASK_COMPLETED`, `TASK_FAILED`, `TASK_INTERRUPTED` |
| `ClientControl.replaceAll` | `CONTROL_CHANGED` (state, controller, ttl), `LEASE_LOST` |
| Reflex reports (via task result / perception) | `REFLEX` — `interrupted_by="reflex"` attribution, ICE §5.3 |
| `ClientDeaths` | `DEATH`, `RESPAWN` |
| `UsageTracker` | `USAGE` (sampled, not per-token) |

Writes come from the client main thread. `McpJournal.append` must be **non-blocking**:
append to the ring, bump seq, `notifyAll` waiters. No I/O, no lock held across a network write.

---

## 4. Dispatch — the placeholder protocol

### 4.1 Two classes of tool

The existing engine already distinguishes them (`ExecuteToolPayload`'s "query fast path"):

- **Query tools** (perception, status, inventory) — resolve in-tick. Return the **real result
  synchronously**. Placeholders here would be pure overhead.
- **World-action tools** (`move_to`, `mine`, `build`, `attack`, `follow`) — enqueue on the
  companion's task queue. Return a **placeholder immediately**.

Classification is read from the tool's own registration, not a hardcoded list.

### 4.2 The placeholder

`tools/call` on a world-action tool returns, within one tick:

```json
{
  "content": [{"type": "text", "text": "{
    \"status\": \"executing\",
    \"taskId\": \"t-7\",
    \"tool\": \"move_to\",
    \"companion\": \"b09de2f9-…\",
    \"dispatchedAt\": {\"seq\": 1042, \"gameTime\": 184220},
    \"guidance\": \"DISPATCHED, NOT COMPLETE. This is not a result and not a failure.
      Do NOT invent an outcome. You may continue reasoning or call other tools that do
      not depend on this one. When you need its outcome, call await_events with
      wake_on.tasks=[\\\"t-7\\\"].\"
  }"}],
  "isError": false
}
```

指导语是占位符协议的灵魂 (ICE §4.2). It appears in **two** places, deliberately:

1. In the `tools/call` **tool description** — lands in the agent's stable prefix, read once.
2. In **every placeholder body** — lands at the attention-heavy tail, read every time.

Without (2), models fabricate outcomes for pending tools. This is not paranoia; it is the
single most likely failure mode of the whole design.

### 4.3 Task lifecycle states

```text
executing ──► completed
          ├─► failed          (tool error, validation)
          ├─► interrupted     ( interrupted_by: "reflex" | "owner_stop" | "death" )
          └─► cancelled       ( cancelled_by: "handover" | "agent" | "companion_removed" )
```

Every terminal state emits a journal event carrying the same `taskId`. **No task may end
without an event** — a stranded `tool_call` id is the one unforgivable bug, because it makes
the agent wait forever for something that already happened.

---

## 5. `await_events` — 停召 as an API call

ICE §5.5 says: when the next step needs an unreturned result and no independent work remains,
*stop calling*, do not spin. Across the harness boundary that becomes one blocking tool.

```json
{
  "name": "await_events",
  "arguments": {
    "since_seq": 1042,
    "timeout_s": 45,
    "wake_on": { "tasks": ["t-7"], "urgent": true, "control": true },
    "companions": ["b09de2f9-…"],
    "max_events": 64
  }
}
```

Returns:

```json
{
  "events": [ /* JournalEvent[], seq-ordered */ ],
  "cursor": 1088,
  "reason": "task_completed",        // | urgent | control | timeout | gap | max_events
  "pending": [
    {"taskId":"t-9","tool":"mine","companion":"b09…","state":"executing","ageSeconds":12}
  ],
  "control": {"b09…": {"state":"EXTERNAL","controller":"claude-code","ttlSeconds":263}}
}
```

Three design points, each load-bearing:

**`timeout_s` must undercut the harness's own tool timeout.** Claude Code will kill a
long-running tool call. Cap server-side at **50 s** regardless of what is requested, and
return an empty batch with `reason:"timeout"` — a cheap, honest "nothing yet, call again".
This is the polling floor, and it is one call per 50 s of idleness rather than one per
curiosity.

**`pending` is ICE §4.6's 活跃清单**, redelivered on every wake. The agent never has to
remember what it dispatched — the mechanism that would otherwise require it to maintain
state across a compaction it does not control.

**`wake_on.urgent` is how the second thinking-gear reaches an external brain.** The reflex
layer handles the millisecond tier in-world (ICE §5.3, default-on, attributed). Early return
on `hostile_proximity` is what lets the agent's *own* fast path start ~immediately rather
than at the next timeout.

### 5.1 Companion tools

- `task_status(taskId | companion)` — poll without waiting, for a model that prefers it.
- `cancel_task(taskId)` — the agent's own abort; emits `cancelled_by:"agent"`.

---

## 6. Sessions, control, and the disconnect path

### 6.1 `McpSession`

```java
record McpSession(String sessionId,          // minted at initialize, opaque
                  String agentId,            // McpConfig.Agent
                  String label,              // clientInfo.name from initialize
                  Map<UUID,String> leases,   // companion -> lease token, PER SESSION
                  AtomicLong cursor,
                  SseSink sink,              // nullable until GET /mcp
                  AtomicLong lastSeenMillis)
```

The global `leaseTokens` map is gone. **A lease belongs to a session, and a session belongs
to an agent.** That is what makes "Claude Code holds Alice, Codex holds Bob" representable.

### 6.2 Transport = session lifecycle

| Verb | Meaning |
|---|---|
| `POST /mcp` + `initialize` | Mint session, return `Mcp-Session-Id` header |
| `POST /mcp` + `Mcp-Session-Id` | Ordinary JSON-RPC |
| `GET /mcp` + `Mcp-Session-Id` | Open SSE; server→client notifications |
| `DELETE /mcp` + `Mcp-Session-Id` | Explicit close → **release every lease** |

Plus an idle reaper: no request for `session_ttl_seconds` (default 120) → close → release.
This is the third of the design's three disconnect nets (the others being the owner-logout
hook and the server-side lease TTL), and it is the only one that notices *the agent* dying
rather than the game.

### 6.3 Control

`acquire_companion` → `NumenActuator.acquire(uuid, label)` → `ControlRequestPayload` →
server `ControlRegistry` → `ControlStatePayload` → `ClientControl` resolves the pending
future → session stores the lease token.

Refusal names the holder (never steals). Every subsequent `tools/call` for that companion
attaches **the session's** token, which the server validates against the live lease. Result:
a stale token from a released session is rejected server-side, satisfying the invariant
without trusting the bridge.

### 6.4 SSE notifications

The SSE stream carries `notifications/message` for journal events. **But the design does not
depend on it**: today's harnesses do not reliably inject mid-turn notifications into the
model's context. SSE is a latency optimisation for clients that can use it; `await_events`
is the contract. Anything delivered by SSE is also readable by cursor. Never one or the other.

---

## 7. Resources and prompts — the pull-once half of parity

Not everything is an event. Persona, protocol and catalogues are **stable prefix material** —
read at join, not streamed.

| URI | Content |
|---|---|
| `numen://protocol` | The world-cognition protocol: seq/provenance semantics, "same topic, highest seq wins", staleness rules, the executing≠done contract |
| `numen://companion/{uuid}/persona` | Composed persona + operating principles (what the built-in brain is told it is) |
| `numen://companion/{uuid}/status` | Snapshot: position, health, hunger, inventory, dimension, control state |
| `numen://companion/{uuid}/conversation` | Owner chat history — **default OFF**, `expose_conversation` flag |
| `numen://usage` | Token/cost accounting, if `expose_usage` |
| `numen://skills` | Skill catalogue |

Prompts: `numen://prompts/onboarding` — the copyable access block (endpoint, agent id, and
where to find the token; **never the token itself in a log**), adapted from upstream `61d2098`.

---

## 8. Threading

- POST handled on a small fixed pool (8) — unchanged, it was fine.
- Every game interaction marshals to the **client main thread** via `Minecraft.execute` —
  `NumenActuator`'s existing contract, unchanged.
- `McpJournal.append` is called **from** the main thread and must never block on it: ring
  write + seq bump + notify. Waiters park on a monitor, not a spin.
- `await_events` parks an HTTP worker thread for up to 50 s. With 8 threads and 3 agents this
  is safe; if agent count grows, move to async response completion rather than growing the pool.
- SSE writes happen off the main thread from a per-sink queue; a slow reader drops to
  cursor-only (`sink.overflow = true`) rather than back-pressuring the game.

---

## 9. Acceptance criteria

Mirrors ICE §9, adapted to the boundary.

```text
 1. 派发即占位:world-action tools/call returns within one tick with status=executing + taskId
 2. 查询直返:query tools return real results synchronously; no placeholder overhead
 3. 并发不阻塞:with a placeholder outstanding, the agent completes >=1 unrelated tool call
 4. 无孤儿:every dispatched task reaches exactly one terminal journal event
    (completed|failed|interrupted|cancelled). Fuzz: kill tasks at every lifecycle point.
 5. 游标可靠:await_events(since_seq=N) returns exactly the events after N, in seq order
 6. 缺口诚实:cursor older than the ring yields a GAP event with the missed count, never
    silent truncation
 7. 早醒:wake_on.tasks fires within one tick of that task's terminal event;
    wake_on.urgent fires within one tick of an urgent perception
 8. 超时廉价:idle await_events returns empty at the cap with reason=timeout; never exceeds
    50s; never returns an error for "nothing happened"
 9. 会话隔离:two sessions holding two different companions never observe each other's lease
    tokens; a token from session A is rejected for session B
10. 断线即释:DELETE /mcp, SSE close, and idle-TTL each release every lease that session held
11. 抢占无伤:a reflex takeover produces interrupted_by="reflex" on the affected task AND a
    REFLEX journal event; the task is never silently dropped
12. 拒绝具名:acquire on a held companion is refused and names the holder; never steals
13. 网络必鉴权:non-loopback bind rejects blank/absent/wrong token on every method incl. GET
14. 端到端:a desktop agent joins, reads numen://protocol, acquires, dispatches a long move_to,
    handles an urgent event via early wake while it runs, receives its completion, releases,
    disconnects — with the built-in brain provably never thinking for that body throughout
```

Criterion 4 is the one that matters most: a stranded task is the failure that makes an agent
wait forever, and it is the failure the old blocking design could not even detect.

---

## 10. Build order

Each slice compiles and is committed on its own.

```text
S1  journal/      JournalEvent, McpJournal (ring/cursor/gap), unit-testable with no MC
S2  transport/    McpHttpServer (POST/GET/DELETE), McpSession, McpSessions, SseSink
                  + protocol/JsonRpc, Dispatch.  Delete McpServer.java here.
S3  tools/        ActuatorTools (placeholder protocol), ControlTools (session-scoped)
S4  tools/        LifecycleTools: await_events, task_status, cancel_task
S5  journal/      JournalSources — the tee from EntityAgentLoop / TaskResult / ClientControl
S6  resources/    McpResources + McpPrompts
S7  runtime       real desktop-agent verification against criteria 1-14
```

S1 is deliberately first and deliberately MC-free: the ring, the seq discipline and the gap
marker are the parts most worth unit tests, and they need no Minecraft toolchain to run —
which, given this project's build history, is worth engineering for on purpose.
```
