# Harness 数据流

> 本文档描述 Harness 的完整业务数据流：请求进入 → 会话加载 → 主 Agent 前置判断（路由分流）→ 两条路径执行 → 统一出口。
> 与「两路径架构」保持一致，以实际编码为准。

***

## 一、总览

```
用户请求 (message / sessionId / agentId)
        │
        ▼
① ChatController（Harness 外壳入口）
     /api/chat         → ChatService.chat()
     /api/chat/stream  → ChatService.streamReactive()
        │
② 加载会话原始数据 + 执行上下文组装
     sessionId → SessionService.loadContext() → 还原 Message 列表
     → 过滤/截断/角色归一化 → ContextAssemblingAdvisor（token 预算控制）
        │
③ 主 Agent 前置判断（路由分流）
     RouteJudge.judge(message) → LlmRouteJudge（一次 LLM 分类，失败兜底 SIMPLE）
        ├─ SIMPLE  → 路径 A：单次 LLM 调用（GeneralAssistantAgent）
        └─ COMPLEX → 路径 B：多 Agent 编排（MultiAgentGraphAgent + StateGraph）
        │
④ 统一出口：会话记忆写回 + 同步/SSE 响应（对外契约一致）
```

***

## 1. 请求入口（Harness 外壳）

| 端点                      | 方法                                                         | 返回                                                      |
| ----------------------- | ---------------------------------------------------------- | ------------------------------------------------------- |
| `POST /api/chat`        | `ChatController.chat()`                                    | `ChatResponse`（JSON）                                    |
| `POST /api/chat/stream` | `ChatController.stream()` → `ChatService.streamReactive()` | `Flux<String>`（SSE：逐 token `data:` + `[DONE]` + `meta`） |

请求体 `ChatRequest`：`message`（必填）、`sessionId`（可选，空则建档）、`agentId`（可选）。

***

## 2. 同步路径 `/api/chat`

```
ChatService.chat(request)
  1. 无 sessionId → sessionService.createSession() 建档
  2. resolveAgent(message)                    ← 主 Agent 前置分流
       └─ routeJudge.judge(message) → COMPLEX → "multi-agent" / 否则 "general"
  3. agentService.executeSync(selectedAgent, message, sessionId)
       ├─ general（A）→ GeneralAssistantAgent.execute(goal)
       │     → buildChatRequestSpec()：按 agent 表 model 取 ChatClient（Registry）
       │       + MessageChatMemoryAdvisor 注入历史 + ContextAssemblingAdvisor 裁剪
       │       + 单次 chatClient.call() → 完整回复
       └─ multi-agent（B）→ MultiAgentGraphAgent.execute(goal)
             → StateGraph：lead 拆解 → 并行子任务 → 聚合 → 最终回答
  4. writeBackContext(sessionId, message, goal)
  5. 返回 ChatResponse {sessionId, newSession, goalId, status, summary}
```

***

## 3. 流式路径 `/api/chat/stream`

```
ChatService.streamReactive(request)
  1. resolveAgent(message)                    ← 主 Agent 分流（同步旁路）
  2. 无 sessionId → boundedElastic 上 createSession() 建档
  3. agent 选择：
       agentId 有值 → executeStreamReactiveByAgentId()
       无 agentId → 按 resolveAgent 结果：
         SIMPLE  → executeStreamReactive(general, ...)
         COMPLEX → executeStreamReactive(multi-agent, ...)
       └─ AgentService：create(sessionId) → markRunning → 订阅 Agent 流
  4. Flux 管道：data: token… → data: [DONE] → event: meta
       （多 Agent 复杂路径：进度行另作 event: progress + data:{stage,detail}；仅内容 token 计入写回摘要）
                 （出错改为 event:error + data:msg）
  5. doOnComplete → writeBackContext() 写回 session_memory（多轮记忆）
```

***

## 4. 主 Agent 前置判断（路由分流，核心薄环）

> 只做「分流」，不执行具体任务，保持入口薄；不改对外契约。

```
RouteJudge.judge(message)（接口）
   └─ LlmRouteJudge.judge(message)
       1. message 为空 → 直接 SIMPLE
       2. clientRegistry.get("route-judge") 取 ChatClient
       3. prompt.system(路由提示词).user(message).call()   ← 一次轻量 LLM
       4. 解析 JSON route：complex → COMPLEX，否则 → SIMPLE
       兜底（任何失败都 SIMPLE，「宁可简单」）：
           异常 / 超时 / 空 / 非 JSON → SIMPLE
```

**提示词**：模型只输出一行 `{"route":"simple"}` 或 `{"route":"complex"}`。

**分流点**：同步在建档后执行前；流式在 async 流开始前（旁路）。`resolveAgent()` 据此选执行体。

***

## 5. 会话与目标生命周期

| 阶段   | 动作                                                | 表                                   |
| ---- | ------------------------------------------------- | ----------------------------------- |
| 建档   | 无 sessionId → `createSession()`                   | `session`                           |
| 历史加载 | `ContextAssemblingAdvisor` + `MessageChatMemoryAdvisor` | 读 `session_messages` |
| 目标创建 | `AgentService` 执行 Goal，`markRunning()`            | `goal`                              |
| 执行   | 同步 `execute()` / 响应式 `stream()`                   | —                                   |
| 记忆写回 | 成功代表 UserMessage+AssistantMessage                 | 写 `session_messages`，`touchSession` |
| 完成   | `SUCCEEDED` / 失败 `FAILED`                         | `goal`                              |

阻塞 DB 操作置于 `Schedulers.boundedElastic()`，避免阻塞响应式循环。

***

## 5a. 上下文组装时序（会话加载 → 组装 → 调 LLM）

> `ContextAssemblingAdvisor` 只整理「已加载的历史 + 当前请求」。

```
GeneralAssistantAgent.buildChatRequestSpec(sessionId, objective)
├─ .advisors(MessageChatMemoryAdvisor.builder(memoryStore).build())
│       → 加载历史：memoryStore.get(conversationId=sessionId)
├─ .advisors(new ContextAssemblingAdvisor())
│        ① filterNoise()    丢弃空 / 空白 / 系统噪音
│        ② normalizeRoles()  system 置前；user/assistant 交替（连续同类保最后）
│        ③ trimToBudget()   token 估算，最旧丢弃（保 system），默认 ≤ 4000
├─ .system(prompt).user(objective)
│  触发 call()/stream() → 重建 Prompt（保留 options/model）
▼
③ 调 LLM：同步 call() / 流式 stream()
```

**关键点**：① 职责解耦（加载 vs 整理）；②纯函数可单测；③保留 system + 最近；④ 同步/流式都覆盖。

***

## 5b. 路径 B 多 Agent 编排数据流（`MultiAgentGraphAgent`）

> `StateGraph`：「lead 拆解 → 并行子任务 → 聚合」，复杂请求（COMPLEX）执行体。流入 `{objective}`，流出 `final`。

```
MultiAgentGraphAgent.execute(goal)
│  构建 StateGraph（构造 compile，结构固定）
│    node：lead → subtask-0..3 → aggregate
│    边：START→lead →(fan-out)→ subtasks →(each)→ aggregate → END
│
│  invoke({objective})
│  ┌ lead 节点 ───────────────────────────┐
│  │  predictLead() → JSON {"subtasks":[…]} │
│  │  → 存 subtaskCount + subtask_0..n-1    │
│  └───────────────┬──────────────────────┘
│                  │ addEdge(lead, List(4)) 并行
│  ┌ subtask-0 ┐ ┌ subtask-1 ┐ … ┌ subtask-3 ┐
│  │ 调 LLM    │ │ 调 LLM    │     （lead 未设则短路）
│  │ → result_0 │ │ → result_1│     │           │
│  └─────┬─────┘ └─────┬─────┘     └─────┬─────┘
│        └─────────────┴─────────────┴─────┘
│                    ▼
│  ┌─ aggregate 节点 ────────────────┐
│  │ 收集 result_{i}（跳过空白），调 LLM │
│  │ → final                         │
│  └───────────────┬────────────────┘
│   invoke → value("final")（无则兜底返回 objective）
└── 统一出口：AgentService / ChatService 写回多轮记忆
```

**关键点**：① Lead 拆 ≤ `MAX_SUBTASKS=4`；② `addEdge(lead,List)` 并行；③ 复用 `ChatClientRegistry`；④ 输出 `final`。

**流式执行（`executeStreamReactive`：「主干帧 + 三条旁路」多通道）**：

> 动机：graph-core 的 `stream()` 按 superstep 吐帧，会把并行分支合并掉，「每个子任务何时完成」在主干里拿不到。
> 于是一路走主干帧覆盖能看到的节点，另一路挂 `GraphLifecycleListener` 补齐分支事件，最后 merge 成一条流。

```
MultiAgentGraphAgent.executeStreamReactive(goal)
│
│ ① 主干：CompiledGraph.stream({objective})（superstep 粒度）
│     START 帧      → 进度行「编排 开始拆解复杂目标…」
│     lead 帧       → 进度行「拆解 N 个子任务已就绪」
│     aggregate 帧  → 进度行「聚合 …」+ 内容行(final 最终回答)
│     END 帧        → （aggregate 已发内容则空，否则兜底 objective）
│         │ concatMap(toRows)
│         ▼
│ ② 旁路：BranchProgressListener（本次执行单独 compile 时注入 CompileConfig）
│     before(subtask-i)：进入节点的 state 含已布置的 subtask_i → 登记 scheduled
│     after (subtask-i)：登记命中才播报（lead 未布置的短路槽位静默，防虚假进度）
│         │ ProgressLine.encode("子任务", "第 i+1 个子任务完成")
│         │ tryEmitSerialized 加锁串行发射 ← 单播 Sink 拒绝并行线程并发发射
│         │   （FAIL_NON_SERIALIZED 会静默丢事件，踩坑记录）
│         ▼
│ ③ 旁路：聚合 token（liveTokens）——聚合节点流式调用逐 token 直推（含首个 token 前「聚合」进度行）
│
│ ④ 旁路：子任务工具事件（toolEvents）——子任务节点经 AgentChatCaller 注入「追踪版工具」
│     （ToolCallTracer 装饰 ToolCallback：schema 原样透传，仅 call 前后发事件）：
│       调用前 → ProgressLine.encode("tool", "WriteFile(/tmp/a.py)")
│       调用后 → ProgressLine.encode("tool-done", "WriteFile ✓ 1.2s · +12/-3 行")
│       （失败 → "WriteFile ✗ 0.3s"；diff 摘要从入参 old/new 串行数近似，仅文件写入/编辑类可得）
│     路径 A（GeneralAssistantAgent.executeStreamReactive）同样以旁路 sink 合并工具事件
│
│ ⑤ 合流：mainLine.mergeWith(branchEvents).mergeWith(liveTokens).mergeWith(toolEvents)
│     ⚠️ 关闸 doFinally(tryCompleteSerialized) 必须挂在 merge **之前**的主干段上：
│        merge 要求两源都终结才传 complete，关闸挂 merge 之后会循环等待 → 死锁（踩坑记录）
```

**行级线协议**（`ProgressLine`，跨组件统一格式）：

| 行类型 | 格式 |
| --- | --- |
| 进度行 | `\u0000stage\u0001detail`（首字符 MARK，非打印字符防撞内容） |
| 内容行 | 无前缀原文（token / 最终回答 final） |

**服务端 → CLI 转换（`ChatServiceImpl.streamReactive`）**：

| Agent 流出行 | SSE 输出 | 记忆写回 |
| --- | --- | --- |
| 进度行 | `event: progress` + `data: {"stage":..,"detail":..}`（Jackson 序列化 `StageRow` record） | 排除，不写回 |
| 内容行 | `data: <row>` | `doOnNext` 收集，`[DONE]` 后统一写回多轮记忆 |

CLI 解析到 `event: progress` 按阶段分派渲染：`编排/聚合` 转 spinner（原位刷新，完成折叠归档灰色 `✓ 阶段 · 耗时`）、`拆解/子任务` 直接归档摘要行、`tool` 转 `⏺ 工具名(参数)` spinner、`tool-done` 归档着色结果行（✓ 绿 / ✗ 红，`+N/-M 行` diff 着色）；最终回答仍按内容流逐 token 呈现打字机效果。

**测试**：`MultiAgentGraphAgentTest`（mock Registry/ChatClient 固定返回，断言进度阶段时序与子任务事件数量）+ `ChatServiceImplTest.streamReactive_shouldMapProgressRowToProgressEvent` + `ToolCallTracerTest`（事件组装/装饰行为/schema 透传）+ `TerminalRendererTest`（工具行渲染）。

***

## 5c. 沙箱工具调用数据流（容器级隔离）

> 模型生成的代码/命令**只在 Docker 容器内执行，宿主机零暴露**（自研宿主机工具 FileTools/SearchTools/ShellTools 已「重合即退役」，无降级路径）。工具面由 `SandboxToolProvider`（agentscope-runtime）提供，`ToolAssignments` 按专家硬性分配。

```
专家子任务执行（coder / analyst / general / researcher）
│
① 工具注入（请求组装期）
   MultiAgentGraphAgent.call() / GeneralAssistantAgent
   → ToolAssignments.forAgent(专家名) → ToolSet{annotated(@Tool), callbacks(ToolCallback)}
   → 流式路径带工具事件发射器：ToolCallTracer 把回调（含 @Tool 转 ToolCallbacks.from）装饰为
     「调用前发 tool / 调用后发 tool-done 进度行」的追踪版（schema 原样透传，模型不可见差异）
   → spec.tools(annotated.toArray()) + spec.toolCallbacks(callbacks)
   ⚠️ 服务端硬边界：未分配的工具 schema 不出服务端，模型不可见也无执行注册
│
② 工具面来源（SandboxToolProvider，懒初始化仅一次）
   首次取用 → 双检锁 → SandboxService.start() + BaseSandbox
   → Docker 拉起容器（镜像 runtime-sandbox-base，容器 :80 → 宿主随机端口）
   → ToolkitInit 把容器能力包装为 12 个 ToolCallback，按安全类别三组：
       执行类×2（RunPythonCode / RunShellCommand）
       只读文件类×6（Read / ReadMultiple / ListDir / DirectoryTree / Search / FileInfo）
       写入类×4（Write / Edit / CreateDir / Move）
   （初始化失败 → 空工具面，warn 不重试，绝不回退宿主机执行）
│
③④ 模型侧循环（LLM tool-calling）
   请求（工具 schema + 任务描述）──────────▶ LLM
   ◀────────── tool_call {name, arguments} ─┐
   │                                         │
   ▼ ④ 服务端执行（Spring AI 框架）           │
   解析 tool_call → 匹配 ToolCallback → 调用   │
   → agentscope-runtime SDK ──HTTP──▶ 容器内 fastapi
                                       └─ 容器内执行 Python/Shell/文件操作（隔离文件系统）
   ◀── stdout / returncode / 文件内容 ──────┘
   → 执行结果作为 tool 消息回传 LLM（含失败也以模型可读文本回传）
│
⑤ 循环 ③④，直至 LLM 不再发起 tool_call → 产出最终回答
```

**专家分配表**（`ToolAssignments`，最小权限）：

| 专家 | @Tool 对象 | Sandbox ToolCallback |
| --- | --- | --- |
| researcher | WebTools（轻量网页抓取，沙箱未覆盖） | 只读文件类 |
| coder | — | 执行类 + 写入类（读→改→跑验证闭环） |
| analyst | — | 执行类 + 只读类 |
| general | WebTools | 全量 |
| writer / 未登记（含 lead、aggregator、multi-agent） | — | 空集（不触发沙箱初始化） |

**关键点**：① 模型生成的代码/命令只落容器，宿主机零暴露；② 空集专家完全不触碰 Docker；③ 容器随 `@PreDestroy` 释放（`SandboxService.close()`），宿主机无残留；④ 执行失败包装为模型可读文本回传，由模型自行调整重试。

**测试**：`ToolAssignmentsTest`（双通道分配语义、EMPTY 不触发沙箱初始化）+ JShell 直连真实验证（容器拉起 → 容器内 Shell returncode=0 → 退出自动删容器，见 `docs/0828-沙箱接入与验证.md`）。

***

## 6. 错误处理

| 环节             | 处理                                        |
| -------------- | ----------------------------------------- |
| 主 Agent 判断失败   | 异常/空/非 JSON → `SIMPLE`，不阻塞                |
| Agent 执行失败（同步） | `ChatResponse.failure`（status=FAILED），不写回 |
| Agent 执行失败（流式） | SSE `event:error`，末尾 `meta.status=FAILED` |
| 未知 agentId     | 兜底 `general`                              |
| lead 拆解失败      | 退化单任务；子任务失败留空，聚合跳过                        |
| 沙箱初始化失败（无 Docker） | 空工具面 + warn（不重试、不回退宿主机），其余功能不受影响        |
| 工具执行失败         | 包装为模型可读文本回传 LLM，由模型调整重试                    |

***

## 7. 组件映射

| 环节         | 组件                                                   |
| ---------- | ---------------------------------------------------- |
| 入口         | `ChatController` / `ChatService` / `AgentService`    |
| 会话加载       | `SessionService.loadContext(sessionId)`              |
| 上下文组装      | `ContextAssemblingAdvisor`                           |
| 主 Agent 判断 | `RouteJudge` / `LlmRouteJudge` / `RouteDecision`     |
| 路径 A（简单）   | `GeneralAssistantAgent`                              |
| 路径 B（复杂）   | `MultiAgentGraphAgent`（StateGraph：lead→并行→aggregate） |
| 统一出口       | `ChatController` 同步 + SSE                            |
| 兜底         | `AgentService` → general                             |
| 沙箱工具面      | `SandboxToolProvider`（agentscope-runtime 容器）+ `ToolAssignments`（专家分配） |

> 更新日期：2026-08-28。

