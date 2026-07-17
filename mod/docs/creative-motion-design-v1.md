# 创造移动重构设计 v1(研究已完成,待实现——"第二波")

**状态**:研究定稿 2026-07-17。第一波(创造破坏三 bug)已落地(cc50a57);本文档是第二波(飞行系统)的施工蓝图。行号引用基于反编译 MC 26.1.2 源码。

## 权威机制结论(源码核实)

- `setGameMode(CREATIVE)` 只置 mayfly/instabuild/invulnerable,**不置 flying**——起飞必须手动 `getAbilities().flying=true`(建议随后 `onUpdateAbilities()`)。
- **无自动取消**:落地/碰撞不会关 flying;唯一取消点是切回 SURVIVAL(空中被切会摔死→见"切换边界")。
- **驱动方式定案:每 tick `setDeltaMovement(velocity)`**,输入位归零。理由:zza/xxa 只有水平(moveRelative 不吃 pitch),yya 垂直仅 0.05/t 且被 travel 的 Y×0.6 覆写;直写速度向量绕过全部输入模型,最鲁棒。
- 速度硬数据:普通飞终端 ≈0.506 b/t(10 b/s),冲刺飞 ≈1.01 b/t(20 b/s,3.6× 地面冲刺),垂直建议 0.375 b/t。**A* 边成本 FLY = 1.0~2.0 tick/格**(地面 WALK≈4.63)→ 飞行天然占优。
- 创造破坏:instabuild 下 START_DESTROY_BLOCK 即毁(ServerPlayerGameMode.handleBlockBreakAction:170-172);剑/三叉戟 canDestroyBlocksInCreative=false 会拒毁(:262);**零掉落**(preventsBlockDrops :283);bedrock 仍不可破。

## 架构定案:混合(直飞执行器 + 独立轻量 3D-A*),**不改地面 A***

地面 A* 的 26+ 处生存假设(canWalkOn/落伤/脚手架)不动;复用 PlayerNav 的 replan/segment/favoring 外壳,新增 FLY 分支。

### 决策树(身体=创造态)
1. 确保 flying=true → 2. 3D raycast 走廊无阻 → 直飞(起飞→巡航→悬停降落)
3. 受阻 → 半径 R 内 3D 空气格 A*(26 邻域,成本=欧氏,+y 折扣天空偏好)绕飞
4. 无解 → 瞬破穿墙,**但 shouldAvoidBreaking(容器/床/功能方块)在创造态保留**——会飞也不拆玩家的家;含保护方块→扩大半径绕行/诚实报"被玩家建筑挡住"
5. 到点:水平<0.35 且 |Δy|<0.35;目标在地面→缓降落地(默认保留 flying 悬停,不摘)

### 切换边界(必做)
`Companions.setGameMode:195` 前加 `preGameModeSwitch` 钩子:creative→survival 且 flying 且空中 → 先取消 FLY 任务缓降至 onGround 再切(推荐),兜底 `resetFallDistance`。

### 工具语义(创造态)
- move_to:飞过去;MoveToTool 文案运行时分叉(剑挡路那句在创造失真)
- auto_mine:已按第一波修复(清除计数 + loot 模拟入包)
- place_block:instabuild 不消耗;仍需背包里有该 BlockItem
- collect_items:创造破坏无掉落,提示语义

### 参数表(FlyTunables)
CRUISE_SPEED 0.5 b/t | SPRINT_CRUISE 1.0 | VERTICAL_SPEED 0.375 | 巡航高度偏好 +2~4 | AVOID_RADIUS 8~16 | HOVER_DEADZONE 0.15 | ARRIVE 0.35/0.35 | FLY_COST 1.0~2.0 | 绕行优先于瞬破

### 文件切分(预估)
新增:pathing/exec/FlyPathExecutor(~250)、pathing/calc/FlyPlanner(~200)、pathing/util/FlyTunables(~40)
改:NavContext(isCreative 已有,+飞行钩 ~30)、PlayerNav(isCreative 分叉 ~40)、引擎 Companions(缓降钩 ~20)+ NumenPlayer(setFlying helper ~15)、MoveToTool 文案
注意:FLY 不写 yRot(setDeltaMovement 定向),头部注视交给 look 系统 hardAim,不与灵动引擎抢输入面。

### 验收场景
①平原直飞 100 格(≈120t,无地面 A*)②飞越峡谷(不搭桥不垫脚零落伤)③洞穴目标(绕 vs 破决策;chest 挡路→守则生效)④创造挖 10 石不挂死(第一波已保证)⑤飞行中切生存不摔死(缓降钩)
