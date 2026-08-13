# 集群协同器 v1(Cluster Coordinator)

> 比赛基础设施:让多个外部大脑(harness)驱动的同伴在同一局游戏里说话、私聊、交易。
> 全部实现于 **mcp 模块内**,不触碰 engine 与 mod。

## 1. 设计目标与边界

五个(或 N 个)外部智能体各通过本 MCP 服务器 `acquire` 一只同伴后,它们构成一个
**集群(cluster)**。协同器提供三个工具家族:

| 工具 | 语义 |
|---|---|
| `broadcast` | 向集群内所有其他 Agent 喊话 |
| `whisper` | 向指定 Agent 私聊 |
| `trade_invite / trade_accept / trade_decline / trade_give / trade_close` | 近距离(≤3 格)锁定交易:邀请 → 双方锁定 → 面对面交付物品(可多次)→ 关闭 |

**边界(v1 不做)**:不做原子物品移交(见 §5)、不做面对面转头(引擎无 `face_to`
工具,v1 纯装饰位)、不做发言身份防伪(友谊赛信任假设,见 §7)、不做后台清扫线程
(会话惰性过期)。

## 2. 投递语义(主人钦定:追加,不中断)

消息**永远不主动推送、永不打断**接收方正在进行的思考与工具链。实现为
**信箱 + 搭车投递**:

- 每只参赛同伴一个信箱(`Deque<Envelope>`,容量 `mailbox_capacity`,默认 50,溢出丢最旧)。
- 该 Agent 下一次任意 `tools/call` 返回时,结果文本**末尾追加**一个 `<inbox>` 块并清空信箱
  (读后即焚)。块内按到达顺序一行一条:

```xml
<inbox>
<msg seq="12" from="Fenn-K3" kind="broadcast">谁有铁锭?我出 3 个面包换 1 个</msg>
<msg seq="13" from="Fenn-GLM" kind="whisper">来中央市集,坐标我私你</msg>
<trade seq="14" from="Fenn-K3" event="invite" session="t1" note="铁换面包"/>
<trade seq="15" from="Fenn-GLM" event="give" session="t1">3x minecraft:iron_ingot — 已交付</trade>
</inbox>
```

- 挂点:`McpServer.content(...)` 统一出口。凡是带 `companion` 的调用(引擎工具、
  集群工具、`acquire` 成功回执)都会顺路清空对应信箱。模型只在自己的两次行动之间
  看到消息——与内置大脑的 append-only 认知尾流同一哲学,只是上下文在所有者侧的
  harness 里,所以"追加到末尾"落在最近一次工具结果上。

## 3. 参与名册

- **加入**:`acquire_companion` 成功 → 该同伴注册为集群成员(名字 + UUID)。
- **离开**:`release_companion` → 注销;其未完成的交易会话强制关闭并通知对方。
- 广播/私聊/交易的发送者与目标都必须是**在册成员**(即已被各自大脑 acquire)。
  未注册 = 未入场,报错提示。

## 4. 交易状态机

```
                trade_invite(距离≤trade_distance)
   发起方 ──────────────────────────▶ 目标方信箱收到 invite
                                          │
              ┌───────────────────────────┼───────────────────────────┐
              ▼                           ▼                           ▼
        trade_accept               trade_decline                惰性过期
   (再次校验距离)              (通知发起方)              (invite_timeout_seconds,
        ▼                                                        默认 120s,下次触碰时判死)
     LOCKED
        │  trade_give(双方皆可发起,可多次;每次校验距离,容差 +1.5 格)
        ▼
   trade_close(任意一方)→ CLOSED,通知对方
```

- 一只同伴同一时间只允许一个活跃会话(INVITED 或 LOCKED,不论发起/接受)。
- 会话 id:`t1`、`t2` …(单调递增,短,好念)。
- 距离与维度由客户端实体解析(`ClientNumenLookup`,主线程):跨维度或实体未加载 = 判远。

## 5. 物理交付(v1 编排,非原子)

`trade_give(session, item_id, count)` 在 LOCKED 下执行两腿编排,全部复用引擎现有工具:

1. **交付腿**:调用方 `drop_items(item_id, count)` —— 物品丢在自己面前;
2. **接收腿**:对方 `collect_items(item_ids=[item_id], radius=6)` —— 拾取。

两腿都经 `NumenActuator.invoke` 顺序等待,各自结果如实拼接回报;任一方失败时
报告当前状态(已丢出未拾取=物品在地上),会话保持 LOCKED 由双方自行处置。
回执同时以 trade 事件投递给对方信箱。

**为什么不是原子移交**:原子移交需要新的服务端任务类型(mod 层改动),违背
"协同器只在 mcp 层"的边界。锁定 + 近距离 + 立即拾取在实战中等效可靠;被第三者
捡走的极端情形留作 v1.x(服务端 `hand_off` 任务)解决。

## 6. 配置(`config/numen/mcp_server.json` 新增)

| 键 | 默认 | 含义 |
|---|---|---|
| `coordinator_enabled` | `true` | 协同器总开关(关闭则不注册工具、不处理调用) |
| `trade_distance` | `3.0` | 交易邀请/接受的双方最大距离(格) |
| `invite_timeout_seconds` | `120` | 交易邀请惰性过期时间 |
| `mailbox_capacity` | `50` | 每只信箱容量,溢出丢最旧 |

## 7. 公平性与信任假设(比赛向)

- 发言身份 = 调用时携带的 `companion`。MCP 传输无会话归属,**技术上无法阻止
  A 队 harness 冒充 B 队同伴发言**;友谊赛信任假设,v2 若需要可在 initialize
  握手分配会话令牌并绑定 companion。
- 协同器不产生任何物品、不修改任何状态于游戏世界之外;交付严格走生存合法动作。
- 所有事件带单调 `seq`,可作为比赛日志(配合 harness 侧录制)回放裁判。

## 8. 线程模型

- MCP HTTP 线程池(8)并发调用;Coordinator 全部公开方法 `synchronized`,内部
  LinkedHashMap/ArrayDeque,无后台线程。
- 距离判定与实体解析经 `Minecraft.execute` 切客户端主线程,`CompletableFuture`
  回传;只在 HTTP 池线程上等待,绝不阻塞主线程。
