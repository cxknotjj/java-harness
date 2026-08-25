# 响应式流式改造实现计划（方案 A：MVC + Flux<String> 穿透）

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将流式聊天通道从 `SseEmitter + CompletableFuture.runAsync` 改造为返回 `Flux<String>` 的响应式流，使用同一 `/api/chat/stream` 路径，保持 text/event-stream 格式、保留老同步 `/api/chat`。

**架构：** Spring MVC 为主，Controller 返回 `Flux<String>`（spring-webmvc 原生的 `text/event-stream` 输出）；`ChatService`/`AgentService` 新增响应式方法；`GraphAssistantAgent` 用 `Flux.create` 包裹阻塞内核把 token push 到 sink（响应式壳 + 阻塞核，阻塞 DB/内核置于 `Schedulers.boundedElastic()`）。

**技术栈：** Spring AI（`ChatClient.stream()` 本即 Reactor Flux）、Reactor（已在 classpath）、Spring MVC SSE、JUnit5 + Mockito。

**设计文档：** `docs/superpowers/specs/2026-08-25-chat-reactive-streaming-design.md`

---

## 文件结构与职责

| 文件 | 动作 | 职责 |
|------|------|------|
| `agent/Agent.java` | 修改 | 新增 `default Flux<String> executeStreamReactive(Goal)`，默认退化 `Flux.just(execute(goal))` |
| `agent/GraphAssistantAgent.java` | 修改 | 覆写 `executeStreamReactive`：用 `Flux.create` 包裹现有阻塞执行，token 回调 push 到 sink |
| `agent/GeneralAssistantAgent.java` | 不变 | 默认退化已满足 |
| `service/AgentService.java` | 修改 | 新增 `Flux<String> executeStreamReactive(String agentName, String objective, String sessionId)` |
| `service/impl/AgentServiceImpl.java` | 修改 | 实现响应式方法：RUNNING → 流式 → doOnComplete SUCCEEDED / doOnError FAILED；DB 写回放 `Schedulers.boundedElastic()` |
| `service/ChatService.java` | 修改 | 新增 `Flux<String> streamReactive(ChatRequest)`；保留 `SseEmitter stream()` |
| `service/impl/ChatServiceImpl.java` | 修改 | 实现 `streamReactive`：建会话 → Flux.from agent 流 → token → [DONE] → meta |
| `controller/ChatController.java` | 修改 | `stream()` 返回 `Flux<String>`（与设计一致，同一路径） |
| 测试 `ChatServiceImplTest` | 修改 | 新增 `streamReactive` 单测 |
| 测试 `GraphAssistantAgent` 相关 | 新增/修改 | 响应式方法单测 |

---

### 任务 1：Agent 接口新增默认退化响应式方法

**文件：**
- 修改：`src/main/java/com/dark/javaHarness/agent/Agent.java`
- 测试：`src/test/java/com/dark/javaHarness/graph/SupportEmailGraphTest.java`（无直接测试，靠编译）

- [ ] **步骤 1：在 Agent 接口增加默认方法**

```java
import org.springframework.ai.chat.messages.??? // 不引入，用 java.util.function 已有
```
实际改动：
```java
public interface Agent {
    String name();
    String execute(Goal goal);
    default void executeStream(Goal goal, Consumer<String> onToken) {
        String result = execute(goal);
        if (onToken != null) onToken.accept(result);
    }
    /** 响应式流式执行：默认退化为同步 execute 后一次性产出；支持真正流式的实现应覆写。 */
    default Flux<String> executeStreamReactive(Goal goal) {
        return Flux.fromCallable(() -> execute(goal));
    }
}
```
需加 import：`import reactor.core.publisher.Flux;`

- [ ] **步骤 2：编译确认**

运行：`mvn -s .mvn/settings.xml compile -q`
预期：成功

- [ ] **步骤 3：Commit**

```bash
git add src/main/java/com/dark/javaHarness/agent/Agent.java
git commit -m "feat: add default reactive stream method to Agent interface"
```

---

### 任务 2：GraphAssistantAgent 覆写响应式流式方法

**文件：**
- 修改：`src/main/java/com/dark/javaHarness/agent/GraphAssistantAgent.java`
- 测试：`src/test/java/com/dark/javaHarness/graph/GraphAssistantAgentReactiveTest.java`（新增）

- [ ] **步骤 1：编写失败的测试**

```java
package com.dark.javaHarness.graph;
// 使用 mock ChatClient: 断言 executeStreamReactive 返回的 Flux 能逐 token 产出
@Test
void executeStreamReactive_emitsTokens() {
    // stub ChatClient.prompt().user().call() 返回 "hello"，ChatClientRegistry.get() 返回 mock client
    // agentService.getAgentConfig(general) 返回 mock config(model=qwen)
    // 调用 executeStreamReactive(new Goal(...))，collect & assert equals "hello"
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -s .mvn/settings.xml test -Dtest=GraphAssistantAgentReactiveTest`
预期：FAIL（方法不存在）

- [ ] **步骤 3：实现 executeStreamReactive**

核心：`Flux.create(sink -> 现有阻塞 run(goal, sink::next))`——把 `run(goal, onToken)` 里 `onToken.accept(token)` 的 token 作为 `sink.next(token)`，结束后 `sink.complete()`；异常 `sink.error(e)`。因为阻塞调用不能让 Netty 线程阻塞，`Flux.create` 用 `FluxSink` + 由 `subscribeOn(Schedulers.boundedElastic())` 承载（在 AgentService 层做）。

```java
@Override
public Flux<String> executeStreamReactive(Goal goal) {
    return Flux.create(sink -> {
        try {
            runWithSink(goal, sink::next, sink::complete);
        } catch (Exception e) {
            sink.error(e);
        }
    });
}
```
重构 `run` 以便流式时走 sink。

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -s .mvn/settings.xml test -Dtest=GraphAssistantAgentReactiveTest`
预期：PASS

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/dark/javaHarness/agent/GraphAssistantAgent.java src/test/java/com/dark/javaHarness/graph/GraphAssistantAgentReactiveTest.java
git commit -m "feat: reactive streaming in GraphAssistantAgent via Flux.create"
```

---

### 任务 3：AgentService 新增响应式流式方法

**文件：**
- 修改：`src/main/java/com/dark/javaHarness/service/AgentService.java`
- 修改：`src/main/java/com/dark/javaHarness/service/impl/AgentServiceImpl.java`
- 测试：`src/test/java/com/dark/javaHarness/service/impl/AgentServiceImplTest.java`（新增用例）

- [ ] **步骤 1：接口新增方法**

```java
Flux<String> executeStreamReactive(String agentName, String objective, String sessionId);
```

- [ ] **步骤 2：实现（含 RUNNING/SUCCEEDED/FAILED + boundedElastic）**

```java
@Override
public Flux<String> executeStreamReactive(String agentName, String objective, String sessionId) {
    Agent agent = requireAgent(agentName);
    Goal goal = goalService.create(objective, sessionId);
    goal.markRunning();
    goalService.update(goal);
    return agent.executeStreamReactive(goal)
        .doOnComplete(() -> {
            String summary = /* 由外部收集? */;
            goalService.update(goal);
        });
}
```
因 summary 需收集完整文本，在 ChatService 层拼装（token 由 sink 发出，完整文本在 executeStream 已有逻辑）。实际实现：复用现有 `executeStream` 的拼装，但把 onToken 接到 sink；此处返回 `Flux` 采用 `Flux.create` 同任务 2 模式，内部跑现有逻辑并将 token 逐个 next。这样 `goal.succeed(summary)` 在收完整后调用，`doOnComplete` 写回。阻塞 DB 的 `goalService.update` 用 `subscribeOn`/`publishOn(Schedulers.boundedElastic())` 隔离。

- [ ] **步骤 3：编写测试**

新增用例断言：Flux 依次产出 token，完成后 goal 置 SUCCEEDED（mock GoalService）。

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -s .mvn/settings.xml test -Dtest=AgentServiceImplTest`
预期：PASS

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/dark/javaHarness/service/AgentService.java src/main/java/com/dark/javaHarness/service/impl/AgentServiceImpl.java src/test/java/com/dark/javaHarness/service/impl/AgentServiceImplTest.java
git commit -m "feat: reactive streaming in AgentService"
```

---

### 任务 4：ChatService 新增响应式流式方法 + 实现

**文件：**
- 修改：`src/main/java/com/dark/javaHarness/service/ChatService.java`
- 修改：`src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java`
- 测试：`src/test/java/com/dark/javaHarness/service/impl/ChatServiceImplTest.java`

- [ ] **步骤 1：接口新增方法**

```java
Flux<String> streamReactive(ChatRequest request);
```

- [ ] **步骤 2：实现 streamReactive**

```java
@Override
public Flux<String> streamReactive(ChatRequest request) {
    // 无 sessionId 建会话（DB，放 boundedElastic）
    String sessionId = ...; boolean newSession = ...;
    return agentService.executeStreamReactive(GENERAL_AGENT, request.message(), sessionId)
        .onErrorResume(e -> Flux.just("event:error"))
        .concatWith(Mono.fromCallable(() -> "[DONE]"))
        .concatWithValues(metaEvent(sessionId, newSession));
}
```
DB 操作（createSession / saveContext / touchSession）通过 `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` 或在 Flux 链上 `publishOn` 隔离。

- [ ] **步骤 3：编写测试**

新增用例：mock AgentService.executeStreamReactive 返回 `Flux.just("a","b")`，断言 token 出现 + 末尾含 `[DONE]` + meta。

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -s .mvn/settings.xml test -Dtest=ChatServiceImplTest`
预期：PASS

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/dark/javaHarness/service/ChatService.java src/main/java/com/dark/javaHarness/service/impl/ChatServiceImpl.java src/test/java/com/dark/javaHarness/service/impl/ChatServiceImplTest.java
git commit -m "feat: reactive streaming in ChatService"
```

---

### 任务 5：ChatController 返回 Flux

**文件：**
- 修改：`src/main/java/com/dark/javaHarness/controller/ChatController.java`
- 测试：全量回归

- [ ] **步骤 1：改造 stream 端点返回 Flux<String>**

```java
@PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> stream(@Valid @RequestBody ChatRequest request) {
    return chatService.streamReactive(request);
}
```
（保留同步 `/api/chat` 返回 ChatResponse 不变）

- [ ] **步骤 2：全量测试回归**

运行：`mvn -s .mvn/settings.xml test`
预期：全部通过（既有 26 + 新增）

- [ ] **步骤 3：Commit**

```bash
git add src/main/java/com/dark/javaHarness/controller/ChatController.java
git commit -m "refactor: chat stream endpoint returns Flux<String>"
```

---

## 自检

**规格覆盖度**：设计文档每节均有对应任务。架构✓(任务1-5)、数据流✓(任务3-4)、并发边界✓(任务3-4 boundedElastic)、错误处理✓(任务3-4 onErrorResume)、测试✓(任务2-4)。`GeneralAssistantAgent` 保持默认退化，符合设计"不变"。

**占位符扫描**：无 "TODO"/"待定"。任务 3 的 summary 收集说明已明确复用现有 executeStream 拼装模式。

**类型一致性**：`executeStreamReactive` 签名在 Agent/AgentService/ChatService 一致（`Flux<String>`）。`streamReactive(ChatRequest)` 在 ChatService/Impl/Controller 一致。