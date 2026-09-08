# Token 预算统一控制 Spec

> 对应 [HARNESS\_TODO.md](../../../docs/HARNESS_TODO.md) L75-78 P0 条目「Token 预算统一控制」。

## Why

预算口径散落多处且只有输入侧裁剪、无消费侧封顶：一次 COMPLEX 编排（lead + 4 子任务 + 聚合）可无上限消耗 token；输出侧全项目未设 `maxTokens`，生成长度不受控（复读循环/跑题长文风险）。

## What Changes

* **集中预算配置扩展**：`ContextBudgetProperties`（`app.context.*`）新增 4 个配置键——输出封顶三档 + 消费侧一档；数值唯一来源是 `application.yaml`，代码零默认（int 缺省 0）（【2026-09-08 评审后两轮调整】最终口径：配置类不含任何预算数字，只做绑定载体；全键统一 0 = 不限制——该层预算关闭，行为等同无预算的存量行为。中途试过「代码默认 = yaml 生产值」的一致性方案，因数字仍写死在 Java 里被否）

* **输出封顶（生成侧防失控，非事后裁剪）**：`AgentChatCaller.buildSpec` 按角色档位设置 `maxTokens`——`max-tokens-lead`（lead 拆解）、`max-tokens-final`（聚合 aggregator + 路径 A 直出对话）、`max-tokens-expert`（编排子任务专家）

* **编排全程消费上限（熔断）**：编排级内存账本（BudgetLedger）按 LLM roundtrip 增量同步累计各节点调用的消耗（真实 usage 优先，无 usage 按估算并沿用 `tokens_estimated` 口径），熔断检查下沉到 caller 两个时点——发起调用前（零 HTTP 短路）与每轮 roundtrip 的 usage 帧到达时（含单次 call 内部工具循环的每轮，超限即断流阻止下一轮）；配合子任务限并发（`subtask-concurrency`）错峰执行收窄在途超额窗口；剩余子任务短路跳过（占位 result 标注「预算超限跳过」），聚合节点必发（record-only 句柄仅记账不熔断）且在 prompt 中注入降级说明【2026-09-08 C1 评审后定为 roundtrip 粒度方案，见下文设计决策】

* **会话维度不设硬拦截**：单次消耗已被输入预算 + maxTokens 双侧封顶、单次编排已被 orchestration-budget 封顶，会话累计只反映正常用量增长——硬拦截会造成「聊着聊着突然不能聊」的体验伤害且无安全收益（换 sessionId 即可绕过）；消费量观测由既有 `llm_call_log` / `GET /api/llm-calls` 承担，零新增代码

* **不改动**：现有输入侧裁剪（history/lead/aggregate/tool-result budget）语义与默认值不变；无 **BREAKING** 变更（新键均有向后兼容默认）

**超出范围（明确不做）**：

* P1 顺带项「子任务结果喂聚合前按预算分摊裁剪」（TODO 已标注 P1，另立任务）

* 会话累计硬配额（原第三档，评估后移除）：单次消耗已被输入预算 + maxTokens 封顶、单次编排已被 orchestration-budget 封顶，会话累计只反映正常用量；硬拦截伤聊天体验且无安全收益

* 熔断后的 Goal 级失败重投（见 P2「Goal 失败重投」）

## Impact

* Affected specs: 无既有 spec；落地后回写 HARNESS\_TODO.md L75-78 勾选状态

* Affected code:

  * [ContextBudgetProperties.java](../../../src/main/java/com/dark/javaHarness/config/ContextBudgetProperties.java) — 新增配置键

  * [application.yaml](../../../src/main/resources/application.yaml) — 生产默认值

  * [AgentChatCaller.java](../../../src/main/java/com/dark/javaHarness/agent/AgentChatCaller.java) — maxTokens 注入 + usage 回调挂点

  * [MultiAgentGraphAgent.java](../../../src/main/java/com/dark/javaHarness/agent/MultiAgentGraphAgent.java) — 编排账本、子任务短路、聚合降级说明

  * 相关测试类：`MultiAgentGraphAgentTest`、`AgentChatCallerTest`、`ContextBudgetProperties` 相关用例

## ADDED Requirements

### Requirement: 集中预算配置扩展

预算项 SHALL 全部收敛于 `ContextBudgetProperties`（`app.context.*` 前缀）统一管理，新增键：

| 配置键                    | 语义             | 生产默认  | 代码默认  |
| ---------------------- | -------------- | ----- | ----- |
| `max-tokens-lead`      | lead 拆解单次输出上限  | 2000  | 0（不限） |
| `max-tokens-final`     | 聚合与路径 A 直出输出上限 | 8000  | 0（不限） |
| `max-tokens-expert`    | 子任务专家单次输出上限    | 4000  | 0（不限） |
| `orchestration-budget` | 单次编排全程消费上限     | 60000 | 0（不限） |

值为 0 或缺省 SHALL 表示不限制（保持现状），保证存量行为兼容。

#### Scenario: 未配置新键时行为不变

* **WHEN** 用户不新增任何 `app.context` 配置启动应用（仅代码默认）

* **THEN** 所有调用行为与改造前一致（不设 maxTokens、无熔断、无配额拒绝）

#### Scenario: 配置集中可查

* **WHEN** 调整任一预算项

* **THEN** 只需修改 `application.yaml` 的 `app.context` 块，无需改代码

### Requirement: 输出封顶（maxTokens 分档）

`AgentChatCaller.buildSpec` SHALL 按调用角色设置 `maxTokens`：lead → `max-tokens-lead`；aggregator → `max-tokens-final`；编排子任务专家 → `max-tokens-expert`；路径 A 直出对话 → `max-tokens-final`（聚合与路径 A 同为直出用户的最终回答，共用一档）。设置发生在生成侧（模型参数），SHALL NOT 对已生成文本做事后裁剪。

#### Scenario: 超长输出截停在限额内

* **WHEN** 子任务专家模型输出超过 `max-tokens-expert`

* **THEN** 生成在限额附近截停（finish\_reason=length），流式通道不中断、子任务结果为限额内的部分内容

* **AND** `llm_call_log` 照常记录本次调用（OK，真实 usage）

#### Scenario: 各节点档位正确

* **WHEN** 编排依次执行 lead / 子任务 / 聚合

* **THEN** 三类调用的 `ChatOptions.maxTokens` 分别等于对应档位配置值（单测捕获 ChatOptions 断言）

### Requirement: 编排全程消费上限（熔断）

一次编排 SHALL 维护内存账本（`AgentChatCaller.BudgetLedger`，`MultiAgentGraphAgent` 按节点注入，同一编排共享同一 `AtomicLong`），按 LLM roundtrip 增量同步累计编排内每次调用的 token 消耗（真实 usage 优先，无 usage 时按输出文本估算并标记，与 `tokens_estimated` 口径一致）。累计 SHALL 在同一调用栈内可读，不依赖 `llm_call_log` 异步落库时序。

熔断检查 SHALL 下沉到 caller，覆盖两个时点（【设计决策】2026-09-08 C1 评审：原「子任务批前检查」在并行扇出下只见 lead 消耗，生产默认配置结构性不可达；采用方向 b——roundtrip 粒度下沉，同时消灭「并行检查时点」与「单次 call 工具循环烧穿」两个盲区）：

* 发起调用前检查：超限 SHALL 零 HTTP 短路（拒绝发起新调用）

* 每轮 roundtrip 的 usage 帧到达时增量记账并复检：超限 SHALL 断流（抛 `BudgetExceededException`，Reactor 取消向上传播关闭 HTTP 连接，工具循环的下一轮不再发起）

子任务并行扇出 SHALL 支持限并发（`subtask-concurrency`，0 = 不限）：经图并行节点信号量排队错峰执行，收窄熔断后的在途超额窗口。

节点句柄 SHALL 区分两类：lead/子任务为门控句柄（受熔断）；聚合为 record-only 句柄（`overBudget` 恒 false，仅记账——聚合必发以产出降级说明与最终回答）。

超限后：

* 未执行的子任务 SHALL 短路跳过（零 LLM 调用），result 占位文本标注「预算超限跳过」；已在途的调用 SHALL 在下一轮 roundtrip 熔断停止

* 聚合节点 SHALL 照常执行（不熔断），聚合 prompt SHALL 注入「N 个子任务因预算超限未执行」的降级说明

* 熔断编排整体 SHALL 以正常终态收尾（非 FAILED），final 为聚合产出（含降级说明）

#### Scenario: 编排内超限熔断

* **WHEN** 模拟 lead + 部分子任务消耗已达 `orchestration-budget`，剩余子任务待执行

* **THEN** 剩余子任务不再发起任何 LLM 调用（单测断言 mock 零交互）

* **AND** 聚合正常调用，final 含「预算超限」降级说明与已消耗/上限数字

* **AND** `llm_call_log` 中本次编排无熔断后新调用记录

#### Scenario: 部分子任务消耗后剩余跳过（真实部分跳过）

* **WHEN** lead 与前序子任务的累计消耗超限（限并发=1 使子任务串行错峰），后续子任务待执行

* **THEN** 前序子任务的真实结果保留进聚合，后续子任务熔断跳过（占位标注），跳过数确定可断言

* **AND** 聚合 prompt 降级说明含部分跳过数（「N 个子任务因预算超限未执行」）

* **AND** 聚合照常产出最终回答（`execute_partialBudgetExhaustion_skipsOnlyRemainingSubtasks`）

#### Scenario: 未超限不受影响

* **WHEN** 编排全程消耗低于上限

* **THEN** 子任务全部执行，行为与现状一致

### Requirement: 口径一致性

所有预算累计 SHALL 复用 `TokenEstimator` 统一估算口径；有真实 usage 时用真实值，无则估算——该标记 SHALL 与 `llm_call_log.tokens_estimated` 字段语义一致（估算 vs 真实可区分），降级说明中的数字 SHALL 注明口径。

#### Scenario: 估算与真实值可区分

* **WHEN** 编排内某调用无 usage 回包（估算）

* **THEN** 账本累计按估算值计入，且熔断降级说明/日志中该数字可被识别为估算口径

