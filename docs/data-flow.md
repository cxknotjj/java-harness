# Harness 数据流

> 本文档描述 Harness 的完整业务数据流：请求进入 → 会话加载 → 主 Agent 前置判断（路由分流）→ 两条路径执行 → 统一出口。
> 与 [HARNESS\_TODO.md](../HARNESS_TODO.md) 的「目标架构」保持一致，并以实际编码为准。

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
        ├─ SIMPLE  → 路径 A：单次 LLM 调用
        └─ COMPLEX → 路径 B：多 Agent 编排（Graph，当前未实现，暂仍走 general）
        │
④ 统一出口：会话记忆写回 + 同步/SSE 响应（对外契约一致）
```

***

## 1. 请求入口（Harness 外壳）

| 端点                      | 方法                                                         | 返回                                                         |
| ----------------------- | ---------------------------------------------------------- | ---------------------------------------------------------- |
| `POST /api/chat`        | `ChatController.chat()` → `ChatService.chat()`             | `ChatResponse`（JSON）                                       |
| `POST /api/chat/stream` | `ChatController.stream()` → `ChatService.streamReactive()` | `Flux<String>`（SSE：逐 token `data:` + 结尾 `[DONE]` + `meta`） |

请求体 `ChatRequest`：`message`（必填）、`sessionId`（可选，空则自动建档）、`agentId`（可选）。

***

## 2. 同步路径 `/api/chat`

```
ChatService.chat(request)
  1. 无 sessionId → sessionService.createSession() 建档（新建会话）
  2. logRoute(message)                                 ← 主 Agent 前置判断
         └─ routeJudge.judge(message) → RouteDecision {SIMPLE|COMPLEX} → 日志
  3. agentService.executeSync(general, message, sessionId)
         └─ GeneralAssistantAgent.execute(goal)
               → newRequest()：按 agent 表 model 取 ChatClient（Registry）
                 + MessageChatMemoryAdvisor.loadContext 注入历史
                 + ContextAssemblingAdvisor 裁剪
                 + 单次 chatClient.call() → 完整回复
  4. writeBackContext(sessionId, message, goal)
         └─ saveContext(user) + saveContext(assistant) + touchSession
  5. 返回 ChatResponse {sessionId, newSession, goalId, status, summary}
```

***

## 3. 流式路径 `/api/chat/stream`

```
ChatService.streamReactive(request)
  1. logRoute(message)     ← 主 Agent 前置判断（同步旁路，进入异步流前）
  2. 无 sessionId → boundedElastic 上 createSession() 建档
  3. 按 agentId 判断执行体：
       agentId 有值 → executeStreamReactiveByAgentId()
       agentId 为空 → executeStreamReactive(general, ...)
         └─ AgentService 层：create(SessionId) → markRunning → 订阅 Agent 流
              → 逐 token 产出
  4. Flux 管道：
       data: token…
       data: [DONE]
       event: meta（含 newSession / status）
       （出错时改为 event:error + data:msg，末尾 meta.status=FAILED）
  5. doOnComplete → writeBackContext() 写回 session_messages（保留多轮记忆）
```

***

## 4. 主 Agent 前置判断（路由分流，核心薄环）

> 只做「分流」，不执行具体任务，保持入口薄；不改对外契约。
> 判断结果仅以日志输出：`[route] message '…' -> SIMPLE/COMPLEX`.

```
RouteJudge.judge(message)   （接口）
   └─ LlmRouteJudge.judge(message)
        1. message 为空 → 直接 SIMPLE
        2. clientRegistry.get("route-judge") 取 ChatClient（Registry 模式）
        3. prompt.system(路由提示词).user(message).call()     ← 一次轻量 LLM
        4. 解析返回值 JSON 的 route 字段：
             complex → COMPLEX；否则 → SIMPLE
        例外兜底（任何失败都 SIMPLE，TODO ⑤「宁可简单」）：
            调用异常 / 超时 / 返回空 / 返回非 JSON → SIMPLE
```

**提示词要求**：模型只输出一行 JSON `{"route":"simple"}` 或 `{"route":"complex"}`。

**接入点**：

- 同步路径：`ChatService.chat()` 在建档后、执行前调用。
- 流式路径：`ChatService.streamReactive()` 进入异步流前调用（同步旁路）。

**分流位点**：决策后仅切换执行链路——`SIMPLE` 走路径 A 单次调用；`COMPLEX` 预留为路径 B 多 Agent Graph（当前未实现，仍走 general 单次调用），Graph 就绪后在 `ChatService` 据此切换。

***

## 5. 会话与目标（Goal）生命周期

| 阶段   | 动作                                                            | 落库                                  |
| ---- | ------------------------------------------------------------- | ----------------------------------- |
| 建档   | 无 sessionId 时 `createSession()`                               | `session` 表                         |
| 历史加载 | `ContextAssemblingAdvisor` + `MessageChatMemoryAdvisor` 还原/裁剪 | 读 `session_messages`                |
| 目标创建 | `AgentService` 执行 `Goal`，`markRunning()`                      | `goal` 表                            |
| 执行   | 同步 `execute()` / 响应式 `stream()`                               | —                                   |
| 记忆写回 | 成功后代 `UserMessage` + `AssistantMessage`                       | 写 `session_messages`，`touchSession` |
| 完成   | `SUCCEEDED` / 失败 `FAILED`                                     | `goal` 表                            |

**并发边界**：阻塞 DB 操作（建档 / 写回 / goal 状态更新）置于 `Schedulers.boundedElastic()`，避免阻塞响应式事件循环。

---

## 5a. 上下文组装时序（会话记忆注入 → 组装 → 调 LLM）

> 对应 `ContextAssemblingAdvisor`：只负责把"已加载的历史 + 当前请求"裁剪成 LLM 友好、受控上下文；不负责从哪里加载历史。

```
GeneralAssistantAgent.buildChatRequestSpec(sessionId, model)
│
│  ① 组装 ChatClientRequestSpec
│
├─ .advisors(MessageChatMemoryAdvisor.builder(memoryStore).build())
│       └─ 加载历史：memoryStore.get(conversationId=sessionId)
│             → 读 session / session_messages 表
│             → List<Message> = [system, user, assistant, user, …]（历史 + 当前）
├─ .advisors(new ContextAssemblingAdvisor())     ← 拦截器，见步骤 ②
├─ .system(config.getSystem()) / .user(objective)   ← system + 当前提问
│
│  ② 触发 call() / stream()，进入 Advisor 链
│
▼ ContextAssemblingAdvisor.adviseCall / adviseStream
│  取 request.prompt().getInstructions() → 完整消息序列（system+历史+本次）
│    ├─ filterNoise()   → 丢弃空 / 空白 / 系统噪声消息
│    ├─ normalizeRoles() → system 置前；user/assistant 交替（连续同类保最后）
│    └─ trimToBudget()  → token 估算，从最旧丢弃（保 system），≤ 默认 4000
│
│  重建 Prompt(assembled, 保留原 options/model) → 交给链
▼
③ 调 LLM：同步 call() 得完整 content / 流式 stream() 逐 token
```

**关键点**

1. **职责解耦**：`MessageChatMemoryAdvisor` 管"加载"，`ContextAssemblingAdvisor` 管"整理"。
2. **纯函数裁剪**：三步都不碰网络，只改内存里的 `messages`，开环可单测。
3. **保留 system + 最近消息**：从最旧丢弃，保证 system 提示词与最近对话语义。
4. **同步/流式都覆盖**：实现 `CallAdvisor` + `StreamAdvisor`，`call()` 与 `stream()` 均受控。
5. **优先级**：`Ordered.HIGHEST_PRECEDENCE+1`，先组装再进调用核心。

---

## 6. 错误处理

| 环节             | 处理                                                      |
| -------------- | ------------------------------------------------------- |
| 主 Agent 判断失败   | LLM 异常 / 解析失败 / 空 → `SIMPLE`，不阻塞请求                      |
| Agent 执行失败（同步） | 返回 `ChatResponse.failure`（status=FAILED），不写回记忆          |
| Agent 执行失败（流式） | SSE 产出 `event:error` + 错误信息，末尾 `meta.status=FAILED`，不写回 |
| 未知 agentId     | `AgentService` 回退默认 `general`                           |

***

## 7. 组件映射

| 数据流环节                 | 组件                                                |
| --------------------- | ------------------------------------------------- |
| 外壳入口                  | `ChatController` / `ChatService` / `AgentService` |
| 会话原始数据加载              | `SessionService.loadContext(sessionId)`           |
| 上下文组装（过滤/截断）          | `ContextAssemblingAdvisor`                        |
| 主 Agent 前置判断          | `RouteJudge` / `LlmRouteJudge` / `RouteDecision`  |
| 路径 A（简单）单次调用          | `GeneralAssistantAgent`                           |
| 路径 B（复杂）多 Agent Graph | （预留 `spring-ai-alibaba-graph-core`，未实现）           |
| 统一出口                  | `ChatController` 同步 + SSE                         |
| 兼容兜底                  | `AgentService` 回退 general                         |

> 更新日期：2026-08-26（纳入 ② 主 Agent 路由判断器后刷新）。

