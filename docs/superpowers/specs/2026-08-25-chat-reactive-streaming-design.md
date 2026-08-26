# 响应式流式改造设计：Chat 流式通道 WebFlux Flux 穿透（方案 A）

> 日期：2026-08-25
> 范围：仅改造流式通道；保留老 `/api/chat` 同步端点与兼容层

## 背景与目标

* 现状：流式聊天 `ChatServiceImpl.stream()` 使用 `SseEmitter` + `CompletableFuture.runAsync`（阻塞线程模型硬接流式）。

* 与 ROADMAP P0「响应式流式改造」一致：`ChatServiceImpl.stream()` 由 `SseEmitter` + `CompletableFuture.runAsync` 改造为 **WebFlux +** **`Flux<String>`** 响应式流。

* Spring AI `ChatClient.stream()` 底层即是 Reactor `Flux`，本项目依赖树已含 `reactor-core` 与 `spring-webflux`（传递引入），`Flux` 类型可直接使用，无需新增 Web 依赖。

**已确认决策**

1. 采用**方案 A**：Spring MVC 为主，Controller 直接返回 `Flux<String>`（`text/event-stream`），最小侵入。
2. 流式端点**沿用同一个** **`/api/chat/stream`** 路径改造（保持 CLI / 前端路径不变）。
3. `Agent` 接口新增**默认退化方法** `executeStreamReactive(Goal)`，保持接口兼容。
4. 同步端点 `POST /api/chat` 与旧 `SseEmitter stream()` 保留（兼容现有 CLI）。

## 架构与数据流

```
POST /api/chat/stream  (Content-Type: text/event-stream)
  → ChatController.stream(): Flux<String>            ← 改造：返回 Flux 而非 SseEmitter
     → ChatService.streamReactive(): Flux<String>    ← 新增：返回 Flux
        → AgentService.executeStreamReactive(...): Flux<String>
           → Agent.executeStreamReactive(Goal): Flux<String>
              → GraphAssistantAgent: 直接返回 LLM 的 spec.stream().content() （去掉 .block()）
响应格式（与现有一致）：
  data: token1
  data: token2
  ...
  data: [DONE]
  event: meta
  data: {"sessionId":"..","newSession":true,"goalId":"..","status":"SUCCEEDED"}
```

## 组件改动

| 层                   | 文件                                   | 改动                                                                                                                                                                  |
| ------------------- | ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 接口 Agent            | `agent/Agent.java`                   | 新增 `default Flux<String> executeStreamReactive(Goal)`，默认返回 `Flux.just(execute(goal))`                                                                               |
| Agent 实现(核心)        | `agent/GraphAssistantAgent.java`     | 覆写 `executeStreamReactive`：去掉 `.block()`，直接返回 `spec.stream().content()`；新增配套的 `compileReactive`（prepare 同步、chat 节点输出 Flux 由外层消费）                                    |
| Agent 实现(兼容)        | `agent/GeneralAssistantAgent.java`   | 不变（默认退化方法已可满足，若需保留同样可覆写）                                                                                                                                            |
| 接口 AgentService     | `service/AgentService.java`          | 新增 `Flux<String> executeStreamReactive(String agentName, String objective, String sessionId)`                                                                       |
| 实现 AgentServiceImpl | `service/impl/AgentServiceImpl.java` | 新增实现：`goal.markRunning` 同步，流式执行，`goal.succeed` 在 `doOnComplete`，异常 `doOnError` 置 FAILED；阻塞 `goalService.update` 放 `Schedulers.boundedElastic()`                     |
| 接口 ChatService      | `service/ChatService.java`           | 新增 `Flux<String> streamReactive(ChatRequest)`；保留 `SseEmitter stream()`                                                                                              |
| 实现 ChatServiceImpl  | `service/impl/ChatServiceImpl.java`  | 新增 `streamReactive`：建会话 → `Flux.from` agent 流 → 逐 token → 末尾拼 `[DONE]` + `meta` 事件；DB 写回（createSession / saveContext / touchSession）放 `Schedulers.boundedElastic()` |
| Controller          | `controller/ChatController.java`     | `stream()` 改为返回 `Flux<String>`，`produces=text/event-stream`                                                                                                         |

## 数据管理 / 并发边界

* **DB 阻塞操作**：`sessionService.createSession`、`saveContext`、`touchSession`、`goalService.create/update` 均为阻塞 JDBC/MyBatis-Plus。按 ROADMAP 注意点，这些调用放入 `Schedulers.boundedElastic()`（在 Flux 流进入 Netty/LLM 线程前完成，或写回阶段切到 boundedElastic），避免阻塞响应式事件循环。

* **会话与 Goal 生命周期**：语义不变——无 sessionId 时自动建档；代理成功 `SUCCEEDED`，失败 `FAILED`。

## 错误处理

* agent 流异常：`onErrorResume` 返回一段 error 事件（`event: error`），并以某个终止事件收尾，保证客户端不会悬挂。

* 流写回阶段 DB 异常：记日志并将 goal 置 FAILED，不中断已发生的 token 推送。

## 测试

* 单测：`ChatServiceImplTest` 增加 `streamReactive` 用例（mock AgentService 返回 Flux，验证 token 依次出现、末尾含 meta、`newSession` 正确）。

* 兼容性回归：既有 26 个测试全绿（不改动同步路径与既有流逻辑的调用）。

## 不在范围（YAGNI）

* 不做 DB 层响应式（MyBatis-Plus 维持阻塞，仅置于调度边界）。

* 不切到纯 WebFlux / Netty（方案 A 保持 MVC 主栈）。

* 不新增依赖；复用已在 classpath 的 `reactor-core` / `spring-webflux` jar（若编译或运行时缺 starter，仅补充 WebFlux starter）。

* 不改 `SseEmitter stream()` 的既有语义供兼容保留。

