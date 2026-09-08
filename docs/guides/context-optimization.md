# 上下文管理优化方案

> 状态：**已实现**（2026-09-06 核对；2026-09-08 增补消费侧预算——输出 maxTokens 分档与编排全程消费上限熔断，见第五节）——`app.context.*` 预算配置（`config/ContextBudgetProperties`）、静态 prompt 预算拦截器（`advisor/PromptBudgetAdvisor`，lead/聚合节点接入）、`tool/ToolCallBudget` 均已落地，由 `MultiAgentGraphAgent` / `GeneralAssistantAgent` 消费；本文保留为设计依据与参数口径说明。
> 关联代码：`advisor/ContextAssemblingAdvisor`、`advisor/PromptBudgetAdvisor`、`tool/ToolCallBudget`、`tool/TokenEstimator`、`agent/MultiAgentGraphAgent`、`agent/AgentChatCaller`
> 背景：262 万 prompt token 事故复盘后，输入侧防线已补齐（工具预算/内容过滤/白名单），本方案补齐最后一个缺口——**静态 prompt 无预算**；消费侧（输出生成与编排累计）防线见第五节。

---

## 一、现状盘点：三条路径，两层防御

### 1.1 上下文入口全景

| 路径 | 节点 | 上下文来源 | 现有防线 |
|------|------|-----------|---------|
| A 简单对话 | general 直答 | 会话历史（DB 逐轮重建） | `MessageChatMemoryAdvisor` + `ContextAssemblingAdvisor`（4000） |
| B 编排 | lead 拆解 | 用户目标 + 内置约束 | ❌ 无 |
| B 编排 | 子任务 ×N | persona + 任务（一次性，零记忆） | ✅ `ToolCallBudget`（12 次硬封顶 + 5k 结果预算） |
| B 编排 | 聚合 | **全部子任务结果顺序拼接** | ❌ **无（最大缺口）** |
| B 续跑 | checkpoint 恢复 | 执行状态（非对话） | ✅ 语义正确，无需预算 |
| 工具层 | 工具 schema | tools 字段 | ✅ MCP 白名单收窄（12 → ≤4） |
| 工具层 | 工具结果内容 | 循环内追加 | ✅ URL 去重 + 3 层内容过滤 + query 提取 |

### 1.2 已确认的问题

1. **聚合节点输入无预算**：子任务的最终回答文本不受 5k 工具预算约束（那只管工具结果），N 路答案在 `MultiAgentGraphAgent` 聚合处 StringBuilder 直拼，单个 prompt 可达 30k~100k+ token——与 262 万事故同构的残留风险。
2. **lead 输入无预算**：用户目标超长时直接进 prompt（低风险，但无上界）。
3. **token 估算口径分裂**：`ContextAssemblingAdvisor` 自带估算，未接入统一的 `TokenEstimator`（目前仅 `ToolCallBudget`、`LlmCallRecorder` 使用），观测日志与预算数值不可比。
4. **预算硬编码**：4000 写死在构造器（代码注释自认"后续可配置注入"）。

### 1.3 防御分层原理（为什么是两个组件而非一个）

Spring AI 的调用管线分两段：

```
prompt().call() ──→ 【Advisor 链】──→ ChatModel.call ──→ 【工具循环】──→ 模型
                      ↑ 每次调用只进一次          ↑ 每轮工具执行后消息追加，advisor 不可见
```

| 层 | 组件 | 管什么 | 时机 |
|----|------|--------|------|
| Advisor 层 | `ContextAssemblingAdvisor`（已有）/ `PromptBudgetAdvisor`（新增） | **静态 prompt**：历史装配、聚合拼接、lead 目标 | 进工具循环之前，拦一次 |
| 工具层 | `ToolCallBudget`（已有） | **动态追加**：工具循环内的工具结果 | 循环内每轮，包装在工具回调上 |

实证：412 报错的生产堆栈中子任务调用已穿过 `ChatModelCallAdvisor.adviseCall`——编排路径的 advisor 机制现成可用，只是链上没有管 prompt 预算的成员。

### 1.4 记忆读写现状：编排 × session memory（不对称）

核实 `ChatServiceImpl.toSseBody → writeBackContext`（流式与 resume 共用）后的精确结论：

| 方向 | 内容 | 说明 |
|------|------|------|
| ✍️ 写入 | `user = 用户目标` + `assistant = 聚合最终回答` | 流结束后服务层一次性写回；**进度行显式过滤**，lead 拆解 JSON / 子任务结果等中间产物不进 memory（只活在 graph state 与 checkpoint） |
| ✍️ 不写 | FAILED 编排 | 出错走 `onErrorResume`，`doOnComplete` 不触发，失败不污染记忆 |
| ⚠️ 重复写 | resume 重放 | 续跑成功会把「目标+新回答」再写一遍；聚合结果与中断前相同时产生重复条目（做摘要注入时顺手去重） |
| 👁️ 读取 | 仅 general 读 | 编排节点（lead/子任务/聚合）**不挂 memory advisor，执行时读不到任何历史**——包括历史编排写回的记录 |

两个推论：

1. **「编排零记忆」的准确表述**是「只写不读」：session memory 里已有历史编排的目标+结论，唯一的读者是 general（编排完成后问 general「刚才的报告说了什么」，它能看到）。
2. **lead 复用记忆的数据基础已存在**——补「只读摘要注入」不需要任何存储改造，实现成本比预想低。

---

## 二、方案设计

### 2.1 新增 `PromptBudgetAdvisor`（P0 核心）

**职责**：请求级拦截，对本次调用的静态 prompt 做 token 预算约束。

**挂载方式**：请求级（`spec.advisors(new PromptBudgetAdvisor(budget))`），**不用 default advisor**——聚合与子任务共用同一个 `ChatClient`（同路由到 qwen3.7-flash），default 挂载会连坐。

**挂载点与预算**：

| 调用点 | 预算 | 截断语义 |
|--------|------|---------|
| 聚合 `aggregate()` | 12k | **按子任务边界等份额截断**：每份保留头尾，中间截断带 `[内容已截断]` 标记；禁止先到先得 |
| lead `lead()` | 4k | 目标文本超长时尾部截断（通用语义） |
| 路径 A 历史 | 4k（维持现状） | `ContextAssemblingAdvisor` 继续负责 |

**实现要点**：

```java
public final class PromptBudgetAdvisor implements CallAdvisor {
    private final int maxTokens;
    // adviseCall: 读 prompt → 估算总 token → 未超预算直通；
    // 超预算则按策略改写 user/system 内容（聚合份额截断 / 通用尾截）→ 放行
    // 截断发生时 log.warn（含原始/预算后 token 数），并在 LlmCallRecorder 观测中可见
}
```

- 截断策略通过构造参数注入（`ProportionalSectionTruncator` / `TailTruncator`），advisor 本体不含业务结构知识——**聚合的子任务边界识别**由调用点传入分隔协议（复用聚合拼接时的既有分隔格式），避免 advisor 反向依赖 agent 包。
- 单位统一走 `TokenEstimator.estimateTokens`，全项目唯一口径。

### 2.2 口径统一与配置化（P1）

1. `ContextAssemblingAdvisor` 的估算改用 `TokenEstimator`（约 3 处替换）。
2. 预算全部挪进 `application.yaml`，集中管理：

```yaml
app:
  context:
    history-budget: 4000      # 路径 A 历史裁剪
    lead-budget: 4000         # lead 拆解 prompt
    aggregate-budget: 12000   # 聚合 prompt
    tool-result-budget: 5000  # 单次调用工具结果总预算（现有）
    tool-call-limit: 12       # 单次调用工具执行次数上限（现有）
```

### 2.3 明确不做的事

- **不硬截断子任务最终输出**：截断聚合输入已足够兜底；截模型输出可能切掉结论，且抑制表达质量。
- **不上 LLM 摘要压缩**（P2 备选）：先用机械截断观察聚合质量，数据证明需要再上 map-reduce 摘要（每个子任务先压 ≤1k 摘要再聚合，成本 +N 次调用）。
- **不给 checkpoint 加对话预算**：它存执行状态不存对话，语义不同不混管。

---

## 三、实施与验收

### 3.1 实施顺序

| 步骤 | 内容 | 改动面 |
|------|------|--------|
| 1 | `PromptBudgetAdvisor` + 两种截断策略 + 单测 | 新增 2 类 + 1 测试类 |
| 2 | 聚合/lead 调用点请求级挂载 | `MultiAgentGraphAgent` 2 处 |
| 3 | `ContextAssemblingAdvisor` 接入 TokenEstimator + 预算配置化 | 1 类 + yaml |
| 4 | 全量回归（179 用例）+ 新增用例 | — |

### 3.2 新增测试用例清单

- `PromptBudgetAdvisorTest`
  - 预算内直通（内容不改写）
  - 聚合语义：多段超预算 → 每份等额、头尾保留、带截断标记
  - 通用语义：单段超预算 → 尾部截断
  - 超限行为：输出 token ≤ 预算（估算口径自洽）
- `MultiAgentGraphAgentTest` 补充：聚合调用携带预算 advisor、lead 调用携带预算 advisor

### 3.3 验收标准

1. 编排链路每个进入模型的 prompt 都有明确 token 上界：lead ≤ 4k、子任务 ≤ 5k（工具结果）+ persona 常量、聚合 ≤ 12k、路径 A ≤ 4k（历史）+ 当轮消息。
2. `LlmCallRecorder` 观测到的 prompt token 与预算组件估算同口径、可比对。
3. 聚合超预算时每个子任务的结果在 prompt 中至少保留等额份额，截断有标记可追溯（log.warn）。
4. 全部预算参数可在 `application.yaml` 调整，无需改代码。

---

## 四、路线图总览

| 优先级 | 事项 | 状态 |
|--------|------|------|
| ~~P0~~ | 工具结果 5k 预算 + 12 次硬封顶 | ✅ 已完成（`ToolCallBudget`） |
| ~~P0~~ | 工具 schema 白名单收窄 | ✅ 已完成（`ToolAssignments`） |
| P0 | `PromptBudgetAdvisor`（聚合 12k + lead 4k） | 📋 本方案 |
| P1 | 口径统一 + 预算配置化 | 📋 本方案 |
| P2 | lead 只读摘要注入：从 session memory 取最近 N 轮压缩 ≤1k 进 lead prompt（只读不写），补「编排丢会话上下文」缺口；实现时顺带处理 resume 重写造成的 memory 重复条目 | 📋 本方案 |
| P2 | map-reduce 分级摘要聚合 | 观察 P0 效果后决定 |
| P2 | 会话/观测表按天归档 | 低优先级 |

### P2 补充设计：lead 只读摘要注入

```
lead prompt = persona（拆解约束）
            + 会话摘要（≤1k：session memory 最近 N 轮压缩，只读不写）
            + 用户目标
```

- **只读不写**：memory 的唯一写入方保持为服务层 `writeBackContext`，lead 不回写，避免拆解 JSON 污染 general 对话历史
- **机械压缩优先**：保留用户消息 + assistant 回答首句，不引入额外 LLM 调用；质量不足再升级为 LLM 摘要
- **收益**：编排入口能感知之前聊定的偏好/口径与历史编排结论，「按我们刚才说好的做」这类指代目标不再失效

---

## 五、消费侧预算（输出封顶 + 编排消费上限熔断，2026-09-08 已实现）

输入侧防线（前四节）只管「喂给模型多少」，不管「模型吐出多少、一次编排总共烧多少」。本节补齐消费侧两道防线。全部预算数值的唯一来源是 `application.yaml` 的 `app.context` 块（代码零默认，`ContextBudgetProperties` 只做绑定载体）；全键统一 **0 = 不限制**——该层预算关闭，行为等同无预算，暂时不需要某项限制时在 yaml 置 0 即可，无需改代码。

### 5.1 输出封顶：maxTokens 按角色分档（生成侧防失控）

在生成侧设 `maxTokens` 防失控（复读循环/跑题长文），**不做事后裁剪**（截断已生成文本会切掉结论、伤表达质量）。`AgentChatCaller.buildSpec` 按调用角色落档：

| 配置键 | 生产默认 | 适用调用 | 档位依据 |
|--------|---------|---------|---------|
| `max-tokens-lead` | 2000 | 编排 lead 拆解 | JSON 中间产物，本就该短 |
| `max-tokens-final` | 8000 | 编排聚合 + 路径 A 直出对话（`GeneralAssistantAgent`） | 两者同为直出用户的最终回答，共用一档 |
| `max-tokens-expert` | 4000 | 编排子任务专家（researcher/coder/analyst/writer/general） | 单路子任务结果 |

- 0 = 不限制：不写入 maxTokens，保持模型默认（全键统一口径，也是配置类缺省值）。
- 生效机制：请求级 `OpenAiChatOptions.maxTokens`，模型在生成侧截停在限额内；model 未配置时同样生效（只覆盖 maxTokens 单字段，不干扰客户端默认模型）。

### 5.2 编排全程消费上限：熔断（`orchestration-budget`）

单次编排（lead + N 子任务 + 聚合）的全程 token 消费上限，默认 60000：

```
input 注入账本（AtomicLong 累计 + AtomicBoolean 估算标记，Replace 策略注册进 state）
  → lead：caller 门控句柄——发起调用前检查 + 每轮 roundtrip usage 帧增量记账并复检
  → 子任务（并行扇出限并发 subtask-concurrency，信号量排队错峰）：同上门控；
    未发起的在预检拒绝（零 HTTP），已在途的在下一轮 roundtrip 熔断断流
    （BudgetExceededException，Reactor 取消关闭 HTTP 连接），result 写
    「预算超限跳过」占位（与「子任务失败无结果」区分）
  → 聚合必发不受熔断（record-only 句柄，仅记账）：占位不计入真实结果，
    prompt 前置【预算降级说明】（N 个子任务未执行 + 已消耗/上限数字 + 消耗口径），
    聚合产出降级版最终回答，编排正常终态（非 FAILED）
```

- **熔断下沉到 caller 的 roundtrip 粒度（2026-09-08 C1 评审修正）**：`AgentChatCaller.BudgetLedger` 在两个时点检查——发起调用前（零 HTTP 短路）与每轮 LLM roundtrip 的 usage 帧到达时（含单次 call 内部工具循环的每轮，超限即断流）。原「子任务批前检查」在并行扇出下各调用共享账本但检查只见 lead 消耗，生产默认配置下熔断结构性不可达，且单次 call 内多轮工具循环可绕过检查烧穿预算——roundtrip 粒度同时消除两个盲区（并行扇出下任一调用的消耗入账后，其余调用的下一轮 roundtrip 立即可见）。
- **同步累计，不依赖落库时序**：按 roundtrip 增量记账（真实 usage 帧 `delta = total - prevTotal`，同一调用栈内可读），`llm_call_log` 异步落库只承担观测，不承担熔断判定。
- **口径**：真实 usage 优先（streamUsage 末帧）；无 usage 回包按输出文本估算并置估算标记（与 `llm_call_log.tokens_estimated` 语义一致），降级说明注明「含估算值」或「真实 usage 统计」。流式与同步路径同口径（流式同样在 usage 帧增量记账 + 无 usage 时估算兜底）。
- **续跑**：断点续跑时新账本随 input 注入覆盖旧值（Replace 策略），已复用的历史节点消耗不计入本次账本（近似口径，可接受）。
- **并行竞态**：并行子任务共享同一账本实例（`AtomicLong` 写安全），熔断为近似判定——检查与累加非原子，超限瞬间在途调用至多再消耗一轮 roundtrip 即被断流；配合 `subtask-concurrency` 限并发错峰，在途超额窗口从「整批」收窄到「至多 N 个在途调用」。熔断目的是止损不是精确计量。

### 5.3 会话累计不设硬配额（评估结论）

单次消耗已被输入预算 + maxTokens 双侧封顶、单次编排已被 orchestration-budget 封顶，会话累计只反映正常用量增长。硬拦截（聊着聊着突然不能聊）体验伤害大且无安全收益（换 sessionId 即可绕过）。消费量观测由既有 `llm_call_log` / `GET /api/llm-calls` 承担，零新增代码。

### 5.4 验收对照

- 模拟编排内超限：剩余子任务零 LLM 调用（mock 零交互断言），聚合带降级说明正常终态 → `MultiAgentGraphAgentTest.execute_overBudget_*` / `execute_withinBudget_*`（同步 + 流式）
- 真实部分跳过（lead + 前序子任务累计超限 → 后续子任务跳过、前序结果保留进聚合）：`MultiAgentGraphAgentTest.execute_partialBudgetExhaustion_skipsOnlyRemainingSubtasks`（限并发=1 使跳过数确定）
- caller 门控/记账：发起前超限零 HTTP、usage 帧真实入账、无 usage 估算兜底、流式与同步同口径 → `AgentChatCallerTest.call_ledgerPreCheckOverBudget_zeroHttp` / `call_realUsageFrame_recordsActualTokens` / `stream_reportsRealUsageToLedger` / `stream_ledgerPreCheckOverBudget_zeroHttp`
- 档位映射：lead/final/expert 三档捕获 `ChatOptions.maxTokens` 断言；0 时不写入 → `AgentChatCallerTest.call_maxTokens*`
