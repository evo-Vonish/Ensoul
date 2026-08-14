# 比赛备战待办(2026-08 全库审查产物)

来源:44,849 行六路并行深读 + 交叉复核,全量证据见审查报告(会话产出)。
审查修复波 1 已随 `b6289c3` 落地(mcp 车道拆分等 8 项,回归 33/33)。

## 波 1 · 比赛前必修(四件,小改动大收益)

- [ ] **SSE 流式超时改看门狗** — engine 流式读取用 120s **整体**超时,thinking 模型长推理必被掐死。改为"无事件 N 秒才算超时"的空闲看门狗。
- [ ] **429/5xx 退避重试** — provider 层零重试,`isRateLimited()` 等判定函数是死代码。指数退避 2~3 次即可挡住瞬断。
- [ ] **拆除错误闩锁 + 修复错误消息落盘** — 一次瞬断后 `aborted` 闩锁让同伴永久植物人;且错误路径产生背靠背 user 消息(GLM/DeepSeek 会话直接变砖)。错误应以 tool/系统消息形式落盘并允许恢复。
- [ ] **GuiTools 合成网格槽位坐标系** — `mod/.../tools/GuiTools.java:44,99` 用 `Slot.index`(容器相对下标)输出网格槽位,`transfer` 按 `menu.slots` 下标寻址 → GUI 合成系统性 off-by-one。网格区与 resultIndex 一律改输 menu.slots 下标(发育赛命门)。

## 波 2 · 行为层与任务层兜底

- [ ] 反射近战打苦力怕(reflexes,与自家 "never melee-trade" 规格冲突)
- [ ] 中立末影人被触发后永久逃跑(加逃逸预算/超时)
- [ ] 溺水"游回来路"死代码(lastAirPos 同 tick 即删)
- [ ] 任务层:同伴移除时 pending 队列补 `cancelAll`(QueuedCalls 永久失踪)
- [ ] 任务层:`tickOne` 加 try/catch 异常隔离(一个任务 bug 不再崩 tick)
- [ ] `/numen reset` 清不动在途 loop(孤儿脑继续烧 API + 交叉写 JSONL)
- [ ] DeepSeekProvider 补 `reasoning_content` 兜底(唯一"重启也修不好"的持久化损坏)
- [ ] inspect_block / inspect_block_storage 加距离/已加载检查(防同步区块加载)
- [ ] HuntTool 描述承诺"报告战后 HP"但实现没有(补数据或删承诺)

## 波 3 · 性能与资源纪律(可以边比赛边修)

- [ ] ScanExecutor 单线程 + PathCaches 每 tick 每同伴 289 次 chunk 查询
- [ ] 区域记忆/地标/JSONL/WireCapture/AgentLoopRegistry 只增不删,补清理路径
- [ ] Gson record 缺字段防御:`Objects.requireNonNull(x, "missing field: ...")` 统一入口
- [ ] InventoryTools.wait 死代码、CombatTools 重复方法等清理

## mcp 开放项(修复波 2 候选)

- [ ] 会话归属令牌:trade_accept/give/close 校验 caller 是 session 当事人(防冒名/防强拆)
- [ ] LOCKED 会话空闲超时(当前只有 INVITED 会过期)
- [ ] 参与者活性收割(长时间无 tools/call 的僵尸参与者)
- [ ] 世界退出/重进时协同器状态重置策略
- [ ] JSON-RPC 错误码细分(-32600 vs -32601)

## 验证环境

沙箱无 JDK 编译链。回归用 ecj + 桩验证:
`/mnt/agents/output/.review-tmp/`(ecj.jar + gson.jar + stubs + TestMain,33 断言)。
