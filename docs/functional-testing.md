# Harness 功能测试说明

> 本文档介绍 Harness 各模块的功能测试内容：每个模块测什么、怎么测、边界在哪。
> 测试代码位于 `src/test/java`，与业务代码同步演进；本文以实际编码为准。
> 更新日期：2026-09-06。

***

## 一、总览

| 测试类 | 覆盖功能域 | 用例数 |
| --- | --- | --- |
| `ChatControllerTest` | 入口层 SSE HTTP 输出契约 | 2 |
| `HarnessControllerTest` | 管理接口（新建会话/切换会话 Agent） | 2 |
| `GlobalExceptionHandlerTest` | 全局异常响应结构 | 3 |
| `ClientAbortLogFilterTest` | 断连日志降噪过滤器 | 7 |
| `ChatServiceImplTest` | 聊天用例编排（会话/记忆/SSE/路由/进度/会话 Agent 同步） | 18 |
| `AgentServiceImplTest` | Agent 执行路由与 Goal 生命周期 | 12 |
| `LlmRouteJudgeTest` | 主 Agent 前置判断（LLM 分流） | 6 |
| `LlmCallRecorderTest` | LLM 调用观测（token 估算/异步落库/失败隔离） | 3 |
| `GoalServiceImplTest` | 启动清理僵尸 RUNNING 目标（保证 /resume 不被 409 拦截） | 1 |
| `SessionServiceImplTest` | 会话切换 Agent（agent_id 校验/幂等/非法入参） | 5 |
| `MultiAgentGraphAgentTest` | 路径 B 多 Agent 编排 + 流式进度 + 记忆注入 + 断点续跑 | 19 |
| `GeneralAssistantAgentTest` | 路径 A 真·逐 token 透传契约 | 1 |
| `AgentChatCallerTest` | 编排调用器（工具幻觉容错重试/异常透传） | 7 |
| `AgentChatCallerRetryTest` | 编排调用器失败自动重试（指数退避） | 4 |
| `AgentRegistryTest` | Agent 注册表（表驱动自动注册/内部角色排除） | 9 |
| `AgentConfigProviderTest` | agent 表配置读取（id→名称映射/模型+提示词） | 10 |
| `MemoryPolicyTest` | 会话记忆注入的角色策略（lead/general 注入，其余不注入） | 8 |
| `PromptAssemblerTest` | system 提示词分段组装（固定次序/优先级） | 8 |
| `ProgressLineTest` | 进度行线协议编解码 | 4 |
| `LlmRetryTest` | LLM 重试策略（可重试判定/退避/耗尽抛错） | 11 |
| `ContextAssemblingAdvisorTest` | 上下文组装（过滤/归一化/token 预算） | 6 |
| `PromptBudgetAdvisorTest` | 静态 prompt 预算（超限尾部截断+截断标记） | 8 |
| `ToolCallBudgetTest` | 工具调用硬预算（超限拦截，返回引导文本） | 7 |
| `ChatClientRegistryTest` | 多服务商模型注册表（禁用回退/未知兜底） | 5 |
| `ChatClientFactoryTest` | ChatClient 工厂 API Key 解析（yaml 映射/环境变量回退） | 5 |
| `ThinkingSwitchChatModelTest` | 思考开关模型层注入（enable_thinking 显式声明优先） | 5 |
| `ModelQuotaExceptionTest` | 配额类硬错误识别与人话转换（402/403） | 6 |
| `ProviderAdminServiceImplTest` | 供应商映射管理（新增/更新/热刷新） | 6 |
| `ToolAssignmentsTest` | 工具分配表（双通道/最小权限） | 8 |
| `ToolCallTracerTest` | 工具调用事件追踪（装饰/schema 透传） | 15 |
| `ToolLazyManagerTest` | 工具 Schema 延迟加载（轻量包装/同请求自愈） | 13 |
| `WebToolsTest` | 网页抓取工具 | 10 |
| `McpToolProviderTest` | MCP 工具懒连接与失败降级 | 8 |
| `SandboxToolProviderTest` | 沙箱初始化超时兜底（无 Docker 不阻塞请求线程） | 3 |
| `TerminalRendererTest` | CLI 渲染（spinner/Markdown/工具行/小结） | 12 |
| `JavaHarnessApplicationTests` | Spring 容器冒烟（contextLoads） | 1 |

**合计：36 个测试类 / 258 个用例，`mvn test` 全部通过（surefire 报告为准）。**

***

## 二、运行方式

```bash
# 全量
mvn -s .mvn/settings.xml test

# 单个测试类
mvn -s .mvn/settings.xml test -Dtest=MultiAgentGraphAgentTest

# 单个用例
mvn -s .mvn/settings.xml test -Dtest=ChatServiceImplTest#streamReactive_shouldMapProgressRowToProgressEvent
```

IDEA 中直接点测试方法旁的绿色按钮即可；报告输出在 `target/surefire-reports/`。

***

## 三、分模块说明

> 本节只展开**代表性模块的核心用例**（非全量清单）：全量以「一、总览」表和 surefire 报告为准，新增用例请先同步总览表。

### 1. `LlmRouteJudgeTest` —— 主 Agent 前置判断

验证「分流」这个核心薄环：LLM 只回一行 JSON，解析结果决定 SIMPLE / COMPLEX。

| 用例 | 验证点 |
| --- | --- |
| `judge_whenLlmReturnsComplex_shouldReturnComplex` | 返回 `{"route":"complex"}` → 判定 COMPLEX（走路径 B） |
| `judge_whenLlmReturnsSimple_shouldReturnSimple` | 返回 simple → 判定 SIMPLE（走路径 A） |
| `judge_whenLlmReturnsInvalidJson_shouldFallbackSimple` | 非 JSON 输出 → 兜底 SIMPLE |
| `judge_whenLlmReturnsBlank_shouldFallbackSimple` | 返回为空 → 兜底 SIMPLE |
| `judge_whenCallThrows_shouldFallbackSimple` | LLM 调用抛异常 → 兜底 SIMPLE，不向上传播 |
| `judge_whenMessageBlank_shouldReturnSimpleWithoutCall` | 消息为空 → 直接 SIMPLE，**不发起 LLM 调用** |

**核心设计**：任何失败都兜底 SIMPLE——「宁可简单，不阻塞请求」。

### 2. `MultiAgentGraphAgentTest` —— 路径 B 多 Agent 编排

mock `ChatClientRegistry` / `ChatClient` 固定 content（lead 固定返回两条子任务的 JSON），整图执行不真实出网。

| 用例 | 验证点 |
| --- | --- |
| `execute_shouldBuildGraphAndReturnFinalAnswer` | 同步路径：StateGraph 构建 + invoke 出最终回答非空 |
| `execute_returnsNonEmptyForAggregate` | 聚合节点把子任务结果汇总为最终回答并原样返回 |
| `executeStreamReactive_emitsProgressRowsAndContent` | 流式路径：进度行（`MARK` 前缀）与内容行混合发射，两类都有 |
| `executeStreamReactive_streamsStageEventsInOrder` | **阶段时序断言**：首行=「编排」→ 存在「拆解」→ ≥2 条「子任务完成」（钩子补齐并行分支，且都在最终回答之前）→ 内容行前紧邻「聚合」，流以最终回答收尾 |

**难点覆盖**：graph-core 的 `stream()` 会合并并行分支帧，「子任务完成」事件靠 `BranchProgressListener`（before/after 钩子配对过滤短路槽位）经 Sink 旁路补齐；时序用例专门防回归。

### 3. `ProgressLineTest` —— 进度行线协议

| 用例 | 验证点 |
| --- | --- |
| `encode_isProgress_shouldBeTrue` | encode 产物以 MARK 开头，被识别为进度行 |
| `encode_decode_shouldRoundTrip` | encode → decode 往返还原 stage/detail |
| `isProgress_shouldBeFalseForPlainContent` | 普通内容 token 不误判为进度行 |
| `decode_shouldReturnNullForPlainContent` | 解码普通内容返回 null（不抛异常） |

### 4. `ChatServiceImplTest` —— 聊天用例编排

覆盖 `/api/chat` 与 `/api/chat/stream` 的完整业务规则。

**会话建档与记忆写回：**

| 用例 | 验证点 |
| --- | --- |
| `chat_withoutSessionId_shouldCreateNewSession` | 无 sessionId → 自动建档，`newSession=true` |
| `chat_withSessionId_shouldReuseSession` | 带 sessionId → 复用不重复建档 |
| `chat_syncSuccess_shouldWriteBackContext` | 同步成功后 user/assistant 写回 `session_messages` |
| `chat_failure_shouldNotWriteBackContext` | FAILED 不污染多轮记忆 |
| `streamReactive_success_shouldWriteBackContext` | 流式成功后同样写回（收集完整回复后统一写入） |
| `streamReactive_onError_shouldNotWriteBackContext` | 流式出错不写回 |

**SSE 契约与分流：**

| 用例 | 验证点 |
| --- | --- |
| `streamReactive_emitsSseTokensAndMeta` | 逐 token `data:` → `data: [DONE]` → `event: meta` 收尾顺序 |
| `streamReactive_onError_emitsErrorEvent` | 出错改发 `event: error` + `meta.status=FAILED`，调用方不悬挂 |
| `chat_shouldInvokeMainAgentRouteJudge` | 同步入口确实经过 RouteJudge 分流 |
| `streamReactive_shouldInvokeMainAgentRouteJudge` | 流式入口同样经过分流 |
| `streamReactive_withAgentId_shouldRouteByAgentId` | 显式 agentId 优先于路由结果 |

**进度流式化（复杂任务体验）：**

| 用例 | 验证点 |
| --- | --- |
| `streamReactive_shouldMapProgressRowToProgressEvent` | 进度行 → `event: progress` + `data: {"stage":..,"detail":..}`，且**不计入**会话摘要 |

### 5. `AgentServiceImplTest` —— Agent 路由与 Goal 生命周期

| 用例 | 验证点 |
| --- | --- |
| `executeStreamReactiveByAgentId_withWriterAgent_routesToWriter` | agentId=2(writer) 正确切换到 writer |
| `executeStreamReactiveByAgentId_withMissingAgent_999_shouldFallbackToGeneral` | 不存在的 agentId 回退 general |
| `executeStreamReactive_emitsTokensAndSucceeds` | goal 创建 → RUNNING → SUCCEEDED 全链路 |
| `executeStreamReactive_onError_marksFailed` | Agent 抛错 → goal 标记 FAILED |

### 6. `ContextAssemblingAdvisorTest` —— 上下文组装

纯函数式单测，覆盖组装三步（filterNoise / normalizeRoles / trimToBudget）：

| 用例 | 验证点 |
| --- | --- |
| `assemble_filtersEmptyMessages` | 空消息与系统占位噪声被丢弃 |
| `assemble_systemPlacedFirst` | system 消息置顶 |
| `assemble_mergesConsecutiveSameRole` | 连续同角色消息交替化（保留最后一条） |
| `assemble_trimsOldMessagesByTokenBudget_keepsRecent` | 超 token 预算从最旧逐条丢弃，保 system + 最近消息 |
| `assemble_underBudget_preservesAll` | 未超预算不动原文 |
| `assemble_preservesModelOptions` | 重建 Prompt 时 model 参数等 options **不被丢失** |

### 7. 配置与注册表

`AgentConfigProviderTest`（agent 表查询）：

| 用例 | 验证点 |
| --- | --- |
| `findAgentNameById_hit / miss / null` | 按 agentId 查名称：命中 / 未命中 / 空参 → Optional.empty |
| `getAgentConfig_hit_shouldReturnModelAndPrompt` | 命中行返回 model + prompt |
| `getAgentConfig_miss_shouldReturnEmpty` / `nullModelShouldBeBlankToNull` | 未查到返回 empty；DB 空串/null 归一化 |

`ChatClientRegistryTest`（model_provider 表 → ChatClient 注册表）：

| 用例 | 验证点 |
| --- | --- |
| `loadFromDatabase_gpt4oRegistered_shouldHit` | 表内启用的模型能命中对应客户端 |
| `loadFromDatabase_disabledDeepseek_shouldFallbackToDefault` | status=0 禁用的模型不注册，get 回退默认 |
| `get_unknownModel_shouldFallbackToDefault` | 完全未知的 model 兜底默认客户端 |

### 8. `ChatControllerTest` —— HTTP 输出层契约

| 用例 | 验证点 |
| --- | --- |
| `stream_eachElementEndsWithNewline` | Flux 每个元素末尾带 `\n`，序列化后每元素独立成行，CLI 可按行解析 |
| `stream_preservesElementOrder` | 元素发射顺序不变 |

### 9. `JavaHarnessApplicationTests` —— 冒烟

`contextLoads()`：Spring 容器全量装配通过（含 MySQL 连接、MyBatis Mapper、各配置 Bean），是最基本的一票否决项。

### 10. `LlmCallRecorderTest` —— LLM 调用观测落库

验证观测旁路的三条契约：估算口径、异步落库、失败隔离。

| 用例 | 验证点 |
| --- | --- |
| `estimateTokens_mixedText_followsAdvisorConvention` | 中英文混合 token 估算与 `ContextAssemblingAdvisor` 同口径（中文 1 token、其它 (长度+3)/4） |
| `record_insertsEntityAsynchronously` | record 后异步落库，字段映射正确（SYNC/OK/total/estimated/duration） |
| `record_mapperFailure_neverThrowsToCaller` | Mapper 抛异常不影响调用方（观测失败永不阻塞主链路） |

***

## 四、测试基建约定

- **mock 边界**：数据库经 H2/spring-test 或 mock Mapper 规避真实依赖；LLM 一律 mock `ChatClient` 返回固定 content——单测不真实出网、结果确定可重复。
- **分层结构**：单元测试为主（Agent/Advisor/Service 层行为），`JavaHarnessApplicationTests` 提供容器级冒烟。
- **防回归重点**：钩子方案的两个踩坑（merge 后关闸死锁、并行线程并发发射丢事件）由 `executeStreamReactive_streamsStageEventsInOrder` 的时序断言兜住；「宁可简单」的分流兜底语义由 `LlmRouteJudgeTest` 六个失败分支覆盖。
- **命名规约**：`方法_条件_期望结果`（如 `judge_whenCallThrows_shouldFallbackSimple`），失败信息一眼定位功能点。

***

## 五、功能 ↔ 测试覆盖矩阵

| 业务功能 | 对应测试 |
| --- | --- |
| 主 Agent 前置分流（SIMPLE/COMPLEX + 兜底） | `LlmRouteJudgeTest`、`ChatServiceImplTest.chat/streamReactive_shouldInvokeMainAgentRouteJudge` |
| 路径 A 单模型对话（上下文注入 + 记忆多轮） | `ChatServiceImplTest`（会话/写回）、`ContextAssemblingAdvisorTest` |
| 路径 B 多 Agent 编排（拆解→并行→聚合） | `MultiAgentGraphAgentTest.execute_*` |
| 复杂任务实时进度推送 | `MultiAgentGraphAgentTest.executeStreamReactive_*`、`ProgressLineTest`、`ChatServiceImplTest.streamReactive_shouldMapProgressRowToProgressEvent` |
| SSE 对外契约（token/DONE/meta/error 按序） | `ChatServiceImplTest.streamReactive_*`、`ChatControllerTest` |
| 多 Agent 显式指定与回退 | `AgentServiceImplTest` |
| agent/model 双表配置驱动 | `AgentConfigProviderTest`、`ChatClientRegistryTest` |
| LLM 调用观测（耗时/token 账本） | `LlmCallRecorderTest` |
