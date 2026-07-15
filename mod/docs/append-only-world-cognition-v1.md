# Append-Only World Cognition v1(追加式世界认知层 v1)

> Observation creates immutable knowledge events.
> Revisits append corrections; they never rewrite prior perception.

Design authority: the project owner's spec (2026-07-16). This document is the
implementation contract for both repos (numen-api engine seams + this tool
pack's policy layer). Cache-first: the whole design exists to keep the prompt
prefix byte-frozen while world knowledge accumulates append-only in the tail.

```
世界真实状态
    ↓ 观察
不可变区域快照 / 变更事件
    ↓ 追加
Agent 上下文中的认知历史
    ↓ 重建
NPC 当前认为世界是什么样
```

## The three layers (never mix them)

| Layer | Question it answers | Lifetime | Where |
|---|---|---|---|
| L1 Landmark Memory | where the important things are | long-lived, semantic | disk store + appended events |
| L2 Regional Observation | what a region looked like when seen | per-region snapshots + diffs | disk event log + appended segments |
| L3 Immediate Perception | what is happening right now | short-lived | tail events only, never enters the map |

Shared epistemology: no observation → no knowledge; no re-observation → old
knowledge may be stale but is never voided in place.

## L1 — Landmark Memory (replaces WorkBlockMemory's context behavior)

Keep: passive harvest from successful tool results; no extra scans; loaded-chunk
verification; unloaded chunks kept on faith ("distance is not evidence of
absence").

Remove: record() remove+put recency reorder; recency-ordered serialization;
per-turn full re-render into the system prefix; unconditional LRU eviction of
the 17th entry (labeled landmarks are exempt from caps).

Split into two objects:

- `LandmarkStore` — canonical disk state `{id, kind, pos, dim, label?, category,
  firstSeen, lastUsedAt, lastVerified, note?}`. Re-use of a known landmark
  updates `lastUsedAt` on disk ONLY: no order change, no emit, zero prompt-byte
  change.
- `LandmarkEventEmitter` — appends context events ONLY on semantic change:
  added / confirmed destroyed / renamed / repurposed / position corrected.

Event shape:

```xml
<landmark_event type="added" id="lm_018">
  <label>基地熔炉</label>
  <dimension>minecraft:overworld</dimension>
  <position x="-17" y="64" z="23"/>
  <kind>minecraft:furnace</kind>
</landmark_event>
```

A destroyed landmark appends a `removed` event; the original `added` event is
never modified.

## L2 — Regional Observation

Region key is 3D: `(dimension, regionX, regionY, regionZ)` with v1 granularity
16×64×16:

```
regionX = floorDiv(x, 16); regionY = floorDiv(y, 64); regionZ = floorDiv(z, 16)
```

`regionY` is mandatory in the schema even if v1 only handles the surface band
(caves / surface / sky sharing rx,rz must never merge into one summary).

### Canonical snapshot (the linchpin)

Never hash prose. Produce a deterministic structured observation first:

```json
{
  "region": {"dimension": "minecraft:overworld", "x": -2, "y": 1, "z": 4},
  "terrain": {"dominant_surface": "minecraft:grass_block",
               "biome": "minecraft:cherry_grove", "height_band": [63, 79]},
  "features": [
    {"type": "building", "bbox": [-28,64,66,-19,74,76],
     "materials": ["minecraft:cherry_planks", "minecraft:cherry_stairs"]},
    {"type": "container", "block": "minecraft:chest", "position": [-22,65,70]}
  ],
  "hazards": [],
  "exits": [{"direction": "west", "kind": "path"}]
}
```

Canonical ordering → strip transient fields → serialize → `semanticHash`.
The natural-language summary shown to the model is RENDERED from this
structure; the hash is computed from the canonical data, never the wording.

### Zero-append semantics

```
进入区域 → 触发条件(跨区域边界,节流;绝不每 tick) → 低成本扫描 → 规范摘要 → semanticHash
  从未观察     → append REGION_SNAPSHOT
  hash 相同    → 零追加
  hash 不同    → 结构化 diff → 过滤无意义变化 → append REGION_DIFF
```

Noise filter (never triggers a diff): grass/flower/leaf randomness, transient
item drops, passing ordinary mobs, fluid animation states, single crop growth
stages, lighting changes, transient player presence.

Importance threshold: block-change count < N with no significant category →
skip. Containers, workstations, doors, redstone, building outline, hazards →
append.

### Confidence & staleness

Each region record carries `{observedAt, confidence: "observed", staleness}`.
`staleness` is computed AT READ TIME (display metadata) — stored facts are
immutable; the "how long ago" rendering lives only in tail notes and
recall results, never in the prefix (otherwise time itself jitters the prompt).

Rendering example: 「你在约 3 个游戏日前观察过该区域。当时这里有一座樱花木屋和
一个箱子。此信息可能已过时。」

### Checkpoints (bounded history)

Trigger: a region accumulates 8–16 diffs, OR diff tokens > 1.5× the first
snapshot, OR a major rebuild. Then append `REGION_CHECKPOINT vN` as the new
baseline; older segments stay in the disk event log and may be archived out of
the active context by compaction. Append-only is preserved: history is never
rewritten, a new baseline is appended.

### recall_region

`recall_region(region_id)` rebuilds current cognition for a region from the
disk event stream and returns it as a tool result (tail append). The full map
never lives permanently in the active context:

```
上下文里保存最近和关键认知
磁盘里保存完整事件历史
工具按需重建远期区域知识
```

## Context placement

```
稳定系统前缀
├── Agent 身份
├── 不变规则
├── 工具定义
└── 世界认知协议(静态说明文:如何读 landmark_event / REGION_* / staleness)

历史事件流(尾部,只追加)
├── 区域首访快照
├── 地标事件
├── 区域 diff / checkpoint
├── 任务和工具结果
└── 即时感知(L3)
```

Never re-summarize the "current world map" back into the head each turn.

## Acceptance tests (write these into the implementation task; all must pass)

1. 同一熔炉连续使用 20 次 → 系统前缀字节完全一致。
2. 首次进入区域 A → 只追加一个 snapshot。
3. 离开 A 进入 B → A 的历史段不得被修改。
4. 回到 A,世界未变化 → 不得追加任何区域事件。
5. 修改 A 的显著建筑后复访 → 只追加一个结构化 diff。
6. A 所在区块未加载 → 不得将旧地标判定为消失。
7. 地标真正被破坏且现场可验证 → 追加 removed 事件,不修改最初 added 事件。
8. 同一观测结果重复扫描 → 规范化 hash 稳定。
9. 麦苗生长、动物路过等噪声 → 不得触发区域 diff。
10. 累计超过阈值 → 追加 checkpoint,并能正确重建当前状态。
11. 缓存回归:连续 50 轮请求记录 prefixHash(引擎在请求组装处打
    `lr-N prefixHash=<sha256(tools+system) 前 12 位>` 日志)——无结构性新知识时
    prefixHash 恒定。

## Rollout

- Wave A (engine, queued): prefix freeze + L1 LandmarkStore/EventEmitter +
  prefixHash log line. (Supersedes the earlier simpler "known_blocks diff" task.)
- Wave B (tool pack): L3 immediate perception policy (hurt/pickup/hunger/…,
  already speced) — in flight.
- Wave C (tool pack): L2 regional observation (region keys, canonical scan,
  hash, diff, checkpoint, recall_region).
