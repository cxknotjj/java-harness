# 客户端断开终止 LLM 请求 Spec

## Why
TODO 项（HARNESS_TODO.md L85）：客户端断开后，服务端应终止大模型请求，避免浪费 token。现状是取消标志（`cancelled`）只在**节点执行前**短路检查——已在途的 LLM 请求（lead 拆解、子任务、聚合）会继续跑完：阻塞 `call()` 走 RestClient/JDK HttpClient 不可中断，流式聚合的内层订阅也未接到取消信号，断连后编排仍烧完剩余 token。

## What Changes
- `AgentChatCaller` 引入取消令牌（`BooleanSupplier cancelled`，可 null）：
  - **BREAKING**（内部 API，非对外）：阻塞 `call()` 改为基于 stream 通道实现（收集全部 token 后返回）——这是 Spring AI 1.1.4 + JDK 连接器下唯一能中止在途 HTTP 请求的方式；副作用：该路径 token 用量从响应 usage 真实值变为按输出文本估算（`tokensEstimated=true`）
  - `stream()` 加 `.takeUntil(cancelled)`：客户端断开后，在途流式调用在下一个 token 边界内中止，取消信号向上传播关闭 HTTP 连接（厂商端停止生成）
  - 取消中止 ≠ 成功完成：已收的部分输出不得按成功返回、不得重试；观测记录为失败且 `error_msg` 标注 client-cancelled
- `MultiAgentGraphAgent`：流式编排路径把共享 `cancelled` 标志传入三个节点（lead/subtask/aggregate）的 caller 调用；同步路径（`/api/chat`，无 SSE 取消语义）维持 `null` 不变
- 节点执行前短路、goal 断连落库 FAILED、`ChatServiceImpl/AgentServiceImpl` 的 doOnCancel 链路：**不变**（已存在）
- 路径 A（GeneralAssistantAgent）：已是响应式链，预期 Reactor cancel 天然中止上游 HTTP——本 spec 只验证，不改代码（若验证发现断链再立新任务）

## Impact
- 受影响 specs：无（首个 spec）
- 受影响代码：
  - `src/main/java/com/dark/javaHarness/agent/AgentChatCaller.java`（核心改造）
  - `src/main/java/com/dark/javaHarness/agent/MultiAgentGraphAgent.java`（传递取消令牌）
  - 测试：`AgentChatCallerTest` / `AgentChatCallerRetryTest` / `MultiAgentGraphAgentTest`（扩展）
- 不受影响：CLI、SSE 协议、`LlmRetry` 退避策略、`LlmCallRecorder` 表结构、路径 A 代码

## ADDED Requirements

### Requirement: 在途 LLM 请求随断连中止
流式编排（COMPLEX 路径）期间客户端断开时，系统 SHALL 在一个 token 间隔内中止所有在途 LLM 请求（关闭上游 HTTP 连接），且不再发起新调用。

#### Scenario: 聚合阶段断连
- **WHEN** 聚合流式输出进行中客户端断开
- **THEN** 聚合的内层流订阅在下一个 token 边界被取消，上游 HTTP 连接关闭
- **AND** llm_call_log 记录一条 `ok=false`、`error_msg` 含 client-cancelled 的记录
- **AND** 不触发重试、不按成功返回部分输出

#### Scenario: 子任务执行中断连
- **WHEN** 某子任务（阻塞语义）执行中客户端断开
- **THEN** 该子任务的在途调用随取消令牌中止（其余并行子任务同样在下一个 token 边界中止）
- **AND** 已完成子任务结果保留、未开始节点短路（既有行为），聚合占位收尾

### Requirement: 取消令牌贯穿 AgentChatCaller
`AgentChatCaller` SHALL 接受可空的 `BooleanSupplier cancelled`：
- 传入且已置位时：`call()`/`stream()` 立即抛取消异常，不发起 HTTP 请求
- 执行中置位时：在下一个 token 边界中止（`takeUntil`），并抛取消异常（区别于正常完成）
- 为 null 时：行为与现状完全一致（同步路径 / 单测场景）

#### Scenario: 断连后不再发起新调用
- **WHEN** cancelled 已置位且节点尚未调用 LLM
- **THEN** caller 直接抛取消异常，零 HTTP 请求（节点级短路守卫之外的兜底）

### Requirement: 阻塞调用改为流式背书（内部）
`call()` SHALL 基于 stream 通道收集完整内容后返回，语义保持：
- 重试策略不变：仅「首个 token 尚未发出」的可重试错误才重试
- 配额硬错误（`ModelQuotaException`）映射不变
- 「未知名工具调用」去工具重试一次的降级不变
- 工具注入 / ToolCallTracer 进度行 / PromptBudgetAdvisor 挂载不变
- token 观测：流式口径估算（`tokensEstimated=true`），记入 llm_call_log

#### Scenario: 正常完成不回归
- **WHEN** 无断连、正常执行编排
- **THEN** lead 拆解 JSON、子任务结果、聚合输出与改造前一致，全部既有测试通过

## MODIFIED Requirements
（无对外契约变更：SSE 协议、REST 接口、CLI 行为均不变）

## REMOVED Requirements
（无）
