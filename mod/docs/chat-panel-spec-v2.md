# 聊天面板布局 v2（主人钦定,权威规格）

**核心理念:前端以消息为主,像聊天软件。所有过程(思考/工具/系统见闻)默认折叠,消息是主角。**

## 顶层聊天流(自上而下)
按对话历史顺序渲染,只出现四种顶层元素:
1. **你的话**(owner message)—— 主体色,无标签。
2. **Fenn 的话**(assistant spoken text)—— 加粗名字头 + 正文。
3. **思考折叠**(thinking)—— 每次 LLM 调用的 reasoning,收起态默认。
   - **流式态**:正在想的时候,`▸ thinking · …最新一句…` 实时滚字(看它在想)。
   - **完成态**:想完收成 `▸ thinking (21.7k chars)` 静态标签(安静不跳)。
   - 点击展开 → 全文(TXT_FAINT)。
4. **步骤汇总行**(step digest)—— **两条相邻 message 之间发生的所有过程的总和**,一行,默认收起:
   - 收起态:`▸ 8 步 · 挖铁矿补装备、回工作台合成…`(步数 + 各步 d 文案拼接,放不下截断)。
   - 展开态 → 见下"步骤时间线"。

## 步骤时间线(步骤汇总行展开后)
按时间顺序铺开这段区间内的每个过程元素,**每个元素自身仍是可折叠子项**:
- **工具行**:默认显示该调用的 `description` 文案(模型写的人话,如 `✓ 挖铁矿补装备`)+ 状态符(spinner 运行 / ✓ 成功 / ✗ 失败)。模型没写 d 时回退工具名(仅名字,不带参数)。点该行展开 → 工具名 + 完整参数 JSON + 结果。
- **思考折叠**:中间轮(工具之间、content 为空的那些轮)的 reasoning,穿插在工具之间的正确时间位置,同样 `▸ thinking (Nk chars)` 可展开。
- **系统见闻**(原"认知事件":周边视觉 sighting / 区域快照 region_snapshot/diff / 止损 system_notice / landmark_event / inference):收起态显系统概要(如 `▸ 系统见闻 ×3 · 含系统通知`),点击展开 → 原始事件文本。

## 三层折叠模型
1. 顶层:消息流 + thinking + 一行步骤汇总。
2. 中层(展开步骤汇总):工具行 / thinking / 系统见闻 的时间线,各自可折叠。
3. 底层(展开单个工具行):该工具的参数与结果。

## 实现依赖
- 需要工具 `description` 参数(工具改造波提供:从 tool_call 的 args 里读 "description" 字段)。
- 复用现有 expandedGroups + foldKey + toggleFoldAt 折叠机制(thinking/tool-group 已在用)。
- 系统见闻的判定复用现有 isCognitiveNote / EVENT_ROOT(f65b389)。
- 步骤汇总行是新的聚合层:把"上一条 message 之后、下一条 message 之前"的所有 assistant 工具调用组 + 中间 thinking + 穿插的 cognition-note user 消息,聚成一个可展开单元。

## 观感目标
安静、留白、像聊天软件。默认状态下,一屏里应该是:你的话、Fenn 的话、几行折叠的灰色摘要。没有一坨 XML、没有一坨 JSON 参数。想深挖时逐层点开。
