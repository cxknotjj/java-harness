# Harness 执行外壳 · 两路径架构 TODO

> 目标架构：把 **Harness** 作为请求的执行外壳，统一从应用层接入；主 Agent 前置判断后，按任务复杂度分流到两条路径，**不是所有请求都强制走复杂多 Agent 流程**。
>
> 现状与目标的差异：下方「✅ 现状」「⬜ 待实现」明确区分哪些已存在于项目、哪些是待办。

***

## 一、目标数据流

```
1. 请求进入 Harness
     请求（message / sessionId / agentId）→ Controller（Harness 外壳入口）
        │
2. 加载会话原始数据 + 执行上下文组装
     sessionId → 读取 session_messages → 还原 Message 列表
     → 过滤 / 截断 / 角色格式化 → 组装成可供 LLM 消费的执行上下文
        │
3. 主 Agent 前置判断（决定走哪条路径）
     ├─ 场景 A：问题简单（无需工具、无需拆分子任务）
     │      → 普通单次大模型调用芯片，直接生成回答 → 结束
     └─ 场景 B：问题复杂（需搜索/代码/多步骤处理）
            → 进入 多 Agent 编排链路（Spring-AI Graph）
                  Lead Agent 拆解 → 子任务 → 子 Agent 并行/串行 → 聚合结果 → 结束
```

***

## 二、TODO 清单（按落地顺序）

### ① Harness 入口与上下文组装（现状：部分已具备）

- [x] **请求接入 Harness 入口**：`ChatController`（`/api/chat` 同步、`/api/chat/stream` 响应式）已是统一入口，ChatService/AgentService 承担外壳编排。
- [x] **加载会话原始数据（session\_message）**：`SessionService.loadContext(sessionId)` 已读取 `session_messages` 的 JSON 快照并还原为 `List<Message>`。
- [x] **执行上下文组装（过滤/截断/角色格式化）**：已由 `ContextAssemblingAdvisor`（Spring AI Advisor 拦截器）实现，挂在 `GeneralAssistantAgent` 的 ChatClient 链上：
  - 按 token 预算裁剪（近似估算，保留 system + 最近 N 轮，从旧丢弃）
  - 过滤空/系统噪声消息
  - 保证 role 顺序（system → user/assistant 交替，压制连续同类）
  - 单测覆盖：见 `ContextAssemblingAdvisorTest`

### ② 主 Agent 前置判断（现状：已具备）✅

- [x] **主 Agent / 路由判断器**：`RouteJudge` 接口 + `LlmRouteJudge` 实现，通过 LLM 判断请求属于「简单(场景A)」还是「复杂(场景B)」。
  - 输入：用户 message
  - 输出：`SIMPLE` / `COMPLEX` 结构化决策（`RouteDecision`）
  - 已接入 `ChatServiceImpl.chat()` / `streamReactive()` 前置分流（日志输出，不改对外契约）
- [x] 主 Agent 判断仅做「分流」，不执行具体任务（入口薄）。
- [x] 判断失败/超时/非 JSON 兜底 `SIMPLE`（宁可简单，TODO ⑤） | 单测覆盖：`LlmRouteJudgeTest`

### ③ 路径 A —— 普通单次调用芯片（现状：已具备）✅

- [x] `GeneralAssistantAgent` 直接单次调用大模型（同步 `call()` / 响应式 `stream()`），携带 session 历史。
- [x] 会话记忆写回（`ChatService.writeBackContext` → `session_messages`）。
- [ ] （可选）将路径 A 的调用从「ChatService 直接调 Agent」抽出，经主 Agent 路由原子化：`A 调用 → GeneralAssistantAgent → 单次 LLM → 返回`。

### ④ 路径 B —— 多 Agent 任务编排（Spring-AI Graph）（现状：已具备）✅

- [x] **引入** **`spring-ai-alibaba-graph-core`**（`pom.xml` 已引入，`repository-jzo2o` 含 1.1.2.2）。
- [x] **新增多 Agent 编排器** **`MultiAgentGraphAgent`**：基于 `StateGraph` 编排，结构固定、编译一次复用：
  - `Lead 节点`：接收复杂目标 → 拆分为至多 `MAX_SUBTASKS=4` 条子任务（JSON 解析，失败退化为单任务）
  - `子任务节点`（subtask-0..3）：`addEdge(lead, List)` 并联扇出，各自独立 ChatClient 单次调用
  - `聚合节点`：按 `subtaskCount` 收集各子任务结果，调用模型汇总为 `final`
- [x] **已在** **`ChatAgentConfig`** **注册** **`multiAgent`** **bean**（agentName=`multi-agent`）。
- [x] **已接入主 Agent 路由**：`ChatServiceImpl.resolveAgent()` 将 COMPLEX 路由到 `multi-agent`、否则 `general`；同步 `chat()` 与流式 `streamReactive()` 均按决策选执行体。
- [x] 复杂路径的执行结果同样走统一的「会话记忆写回 + SSE/同步响应」出口（`ChatService.writeBackContext` / `ChatController`），两条路径对外契约一致。
- [ ] 为复杂路径注册 Checkpointer（可选，落库断点）——当前未引入，如需再评估。

**单测覆盖**：`MultiAgentGraphAgentTest`（mock ChatClient 验证并行子任务+聚合）、`ChatServiceImplTest`（COMPLEX→`executeStreamReactive("multi-agent")`）。

- [ ] **抽离项目中的常量，将他们整合在enums文件夹下，方便统一管理**

**问题：目前在多agent并发执行时，会触发线程崩溃，导致**

2026-08-26T21:27:10.517+08:00 ERROR 22028 --- \[javaHarness] \[nio-8080-exec-5] c.d.j.exception.GlobalExceptionHandler : 未处理异常

org.springframework.web.context.request.async.AsyncRequestNotUsableException: Servlet container error notification for disconnected client
at org.springframework.web.context.request.async.WebAsyncManager.lambda$startDeferredResultProcessing$6(WebAsyncManager.java:451) \~\[spring-web-6.2.18.jar:6.2.18]
at org.springframework.web.context.request.async.StandardServletAsyncWebRequest.lambda$onError$0(StandardServletAsyncWebRequest.java:195) \~\[spring-web-6.2.18.jar:6.2.18]
at java.base/java.util.ArrayList.forEach(ArrayList.java:1511) \~\[na:na]
at org.springframework.web.context.request.async.StandardServletAsyncWebRequest.onError(StandardServletAsyncWebRequest.java:195) \~\[spring-web-6.2.18.jar:6.2.18]
at org.apache.catalina.core.AsyncListenerWrapper.fireOnError(AsyncListenerWrapper.java:49) \~\[tomcat-embed-core-10.1.54.jar:10.1.54]
at org.apache.catalina.core.AsyncContextImpl.setErrorState(AsyncContextImpl.java:413) \~\[tomcat-embed-core-10.1.54.jar:10.1.54]
at org.apache.catalina.connector.CoyoteAdapter.asyncDispatch(CoyoteAdapter.java:153) \~\[tomcat-embed-core-10.1.54.jar:10.1.54]
at org.apache.coyote.AbstractProcessor.dispatch(AbstractProcessor.java:243) \~\[tomcat-embed-core-10.1.54.jar:10.1.54]
at org.apache.coyote.AbstractProcessorLight.process(AbstractProcessorLight.java:57) \~\[tomcat-embed-core-10.1.54.jar:10.1.54]
at org.apache.coyote.AbstractProtocol$ConnectionHandler.process(AbstractProtocol.java:903) \~\[tomcat-embed-core-10.1.54.jar:10.1.54]
at org.apache.tomcat.util.net.NioEndpoint$SocketProcessor.doRun(NioEndpoint.java:1797) \~\[tomcat-embed-core-10.1.54.jar:10.1.54]
at org.apache.tomcat.util.net.SocketProcessorBase.run(SocketProcessorBase.java:52) \~\[tomcat-embed-core-10.1.54.jar:10.1.54]
at org.apache.tomcat.util.threads.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:973) \~\[tomcat-embed-core-10.1.54.jar:10.1.54]
at org.apache.tomcat.util.threads.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:491) \~\[tomcat-embed-core-10.1.54.jar:10.1.54]
at org.apache.tomcat.util.threads.TaskThread$WrappingRunnable.run(TaskThread.java:63) \~\[tomcat-embed-core-10.1.54.jar:10.1.54]
at java.base/java.lang.Thread.run(Thread.java:842) \~\[na:na]
Caused by: java.io.IOException: Connection reset by peer
at java.base/sun.nio.ch.SocketDispatcher.write0(Native Method) \~\[na:na]
at java.base/sun.nio.ch.SocketDispatcher.write(SocketDispatcher.java:54) \~\[na:na]
at java.base/sun.nio.ch.IOUtil.writeFromNativeBuffer(IOUtil.java:132) \~\[na:na]
at java.base/sun.nio.ch.IOUtil.write(IOUtil.java:97) \~\[na:na]
at java.base/sun.nio.ch.IOUtil.write(IOUtil.java:53) \~\[na:na]
at java.base/sun.nio.ch.SocketChannelImpl.write(SocketChannelImpl.java:532) \~\[na:na]
at org.apache.tomcat.util.net.NioChannel.write(NioChannel.java:129) \~\[tomcat-embed-core-10.1.54.jar:10.1.54]
at org.apache.tomcat.util.net.NioEndpoint$NioSocketWrapper.doWrite(NioEndpoint.java:1436) \~\[tomcat-embed-core-10.1.54.jar:10.1.54]
at org.apache.tomcat.util.net.SocketWrapperBase.doWrite(SocketWrapperBase.java:748) \~\[tomcat-embed-core-10.1.54.jar:10.1.54]
at org.apache.tomcat.util.net.SocketWrapperBase.flushBlocking(SocketWrapperBase.java:714) \~\[tomcat-embed-core-10.1.54.jar:10.1.54]
at org.apache.tomcat.util.net.SocketWrapperBase.flush(SocketWrapperBase.java:699) \~\[tomcat-embed-core-10.1.54.jar:10.1.54]
at org.apache.coyote.http11.Http11OutputBuffer$SocketOutputBuffer.flush(Http11OutputBuffer.java:574) \~\[tomcat-embed-core-10.1.54.jar:10.1.54]
at org.apache.coyote.http11.filters.ChunkedOutputFilter.flush(ChunkedOutputFilter.java:154) \~\[tomcat-embed-core-10.1.54.jar:10.1.54]
at org.apache.coyote.http11.Http11OutputBuffer.flush(Http11OutputBuffer.java:216) \~\[tomcat-embed-core-10.1.54.jar:10.1.54]
at org.apache.coyote.http11.Http11Processor.flush(Http11Processor.java:1283) \~\[tomcat-embed-core-10.1.54.jar:10.1.54]
at org.apache.coyote.AbstractProcessor.action(AbstractProcessor.java:408) \~\[tomcat-embed-core-10.1.54.jar:10.1.54]
at org.apache.coyote.Response.action(Response.java:201) \~\[tomcat-embed-core-10.1.54.jar:10.1.54]
at org.apache.catalina.connector.OutputBuffer.doFlush(OutputBuffer.java:298) \~\[tomcat-embed-core-10.1.54.jar:10.1.54]
at org.apache.catalina.connector.OutputBuffer.flush(OutputBuffer.java:264) \~\[tomcat-embed-core-10.1.54.jar:10.1.54]
at org.apache.catalina.connector.CoyoteOutputStream.flush(CoyoteOutputStream.java:130) \~\[tomcat-embed-core-10.1.54.jar:10.1.54]
at org.springframework.web.context.request.async.StandardServletAsyncWebRequest$LifecycleServletOutputStream.flush(StandardServletAsyncWebRequest.java:415) \~\[spring-web-6.2.18.jar:6.2.18]
at org.springframework.util.StreamUtils.copy(StreamUtils.java:137) \~\[spring-core-6.2.18.jar:6.2.18]
at org.springframework.http.converter.StringHttpMessageConverter.writeInternal(StringHttpMessageConverter.java:128) \~\[spring-web-6.2.18.jar:6.2.18]
at org.springframework.http.converter.StringHttpMessageConverter.writeInternal(StringHttpMessageConverter.java:44) \~\[spring-web-6.2.18.jar:6.2.18]
at org.springframework.http.converter.AbstractHttpMessageConverter.write(AbstractHttpMessageConverter.java:234) \~\[spring-web-6.2.18.jar:6.2.18]
at org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitterReturnValueHandler$DefaultSseEmitterHandler.sendInternal(ResponseBodyEmitterReturnValueHandler.java:315) \~\[spring-webmvc-6.2.18.jar:6.2.18]
at org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitterReturnValueHandler$DefaultSseEmitterHandler.send(ResponseBodyEmitterReturnValueHandler.java:295) \~\[spring-webmvc-6.2.18.jar:6.2.18]
at org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.send(ResponseBodyEmitter.java:211) \~\[spring-webmvc-6.2.18.jar:6.2.18]
at org.springframework.web.servlet.mvc.method.annotation.ReactiveTypeHandler$TextEmitterSubscriber.send(ReactiveTypeHandler.java:507) \~\[spring-webmvc-6.2.18.jar:6.2.18]
at org.springframework.web.servlet.mvc.method.annotation.ReactiveTypeHandler$AbstractEmitterSubscriber.run(ReactiveTypeHandler.java:371) \~\[spring-webmvc-6.2.18.jar:6.2.18]
at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136) \~\[na:na]
at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635) \~\[na:na]
... 1 common frames omitted的报错

- [ ] **任务状态推送**：注意的点

  1.不要写死在各个agent业务节点，可以作为advisor或者中间件来实行。

### ⑤ 兼容兜底与统一出口（现状：部分已具备）✅/⬜

- [x] 未命中 agentId / 判断失败时回退默认 `general`（简单路径）——已具备兜底。
- [x] 两条路径共享统一响应出口（`ChatController` 同步/SSE）。
- [x] 主 Agent 判断异常时**兜底走简单路径**（宁可简单，不强行走复杂流程）——`resolveAgent()` 内 catch 返回默认 `general`。

***

## 三、组件映射（现有项目 → 目标架构）

| 目标架构角色                 | 现有实现 / 预留                                                   |
| ---------------------- | ----------------------------------------------------------- |
| Harness 外壳入口           | `ChatController` / `ChatService` / `AgentService`           |
| 会话原始数据加载               | `SessionService.loadContext(sessionId)`                     |
| 上下文组装(过滤/截断)           | `ContextAssemblingAdvisor`                                  |
| 主 Agent 前置判断           | `RouteJudge` / `LlmRouteJudge`                              |
| 路径 A(简单) 单次调用          | `GeneralAssistantAgent`                                     |
| 路径 B(复杂) 多 Agent Graph | `MultiAgentGraphAgent`（基于 `StateGraph`：lead→并行子任务→聚合→final） |
| 统一响应出口                 | `ChatController` 同步 + 响应式 SSE                               |
| 兼容兜底                   | `AgentService` 回退 general                                   |

***

## 四、验收标准

- [x] 一个简单请求（如「你好」「讲个笑话」）→ 走路径 A，单次调用返回，无多余 Graph 开销。
- [x] 一个复杂请求（如「写一个项目并调研竞品，输出报告」）→ 主 Agent 判定为复杂，走路径 B 多 Agent Graph。
- [x] 主 Agent 判断失败/超时 → 兜底走路径 A，不阻塞请求。
- [ ] 两条路径均保留多轮会话记忆与 SSE 流式输出，对外响应格式一致（流式复杂路径当前为聚合后整块响应，需进一步演进为 Graph 内逐 token 流式）。

