# javaHarness Roadmap & TODO

> 由原 `ROADMAP.md` 与 `HARNESS_TODO.md` 合并而成。
> 约定：`- [ ]` 未开始，`- [x]` 已完成。**未完成在前（按优先级），已完成在后（存档备查）**。
> 版本强约束：Spring Boot 保持 3.5.14（兼容 Spring AI 1.1.4），升级任何 AI 相关依赖前先验证兼容性。

***

## 目标架构（两路径数据流）

```
1. 请求进入 Harness
     请求（message / sessionId / agentId）→ ChatController（统一入口）
        │
2. 加载会话原始数据 + 上下文组装
     sessionId → session_messages 还原 Message 列表
     → ContextAssemblingAdvisor 过滤 / 截断 / 角色格式化
        │
3. 主 Agent 前置判断（RouteJudge，LLM 决策 SIMPLE / COMPLEX，失败兜底 SIMPLE）
     ├─ 场景 A 简单 → GeneralAssistantAgent 单次调用（真·逐 token 流式）→ 结束
     └─ 场景 B 复杂 → MultiAgentGraphAgent 编排（StateGraph：lead 拆解 → 并行子任务 → 聚合）
                       生命周期钩子旁路推送执行进度（event: progress SSE）→ 结束
```

| 目标架构角色 | 现有实现 |
| --- | --- |
| Harness 外壳入口 | `ChatController` / `ChatService` / `AgentService` |
| 会话原始数据加载 | `SessionService.loadContext(sessionId)` |
| 上下文组装(过滤/截断) | `ContextAssemblingAdvisor` |
| 主 Agent 前置判断 | `RouteJudge` / `LlmRouteJudge` |
| 路径 A(简单) 单次调用 | `GeneralAssistantAgent` |
| 路径 B(复杂) 多 Agent Graph | `MultiAgentGraphAgent`（lead→并行子任务→聚合→final） |
| 统一响应出口 | `ChatController` 同步 + 响应式 SSE |
| 兼容兜底 | `AgentService` 回退 general |

***

# 一、未完成 TODO

## P0 · 架构收尾（眼前优先）

- [ ] **补齐 agent 表配置**：新增 `agentName='multi-agent'` 记录并指定模型，消除「使用默认配置」退回（现状：编排全节点挤在默认客户端+兜底 prompt 上）。
- [ ] **专家 agent 派遣**：让 lead 在拆解 JSON 里为每条子任务输出 `agentId`，subtask 节点按此从 `ChatClientRegistry` 取对应专家客户端执行。改动点在 lead 的拆解提示词和 `MultiAgentGraphAgent` 子任务节点的取客户端逻辑，属于下一个迭代量级。
- [ ] **Graph 内逐 token 流式**：复杂路径当前只做到「阶段进度实时推送 + 聚合结果一次性输出」，子任务节点仍是阻塞单次调用；演进为 Graph 各节点产出逐 token 流。
- [ ] **并发断连的可观测处理**：多 Agent 并发执行期间若客户端断开（超时/退出），Tomcat 会报 `AsyncRequestNotUsableException`（Connection reset by peer）。根因（CLI 超时过短、伪流式等待）已修复，服务端还需把「下游断开」降噪为 warn 并及时终止编排，避免无谓消耗。
- [ ] 为复杂路径注册 Checkpointer（可选，落库断点）——如需再评估。

## P1 · 能力增强（核心扩展）

- [ ] **RAG 知识库**：引入向量库（pgvector 优先，因其已在依赖管理中）+ Spring AI `VectorStore`
  - 验收：能对本地文档做"知识库问答"
- [ ] **MCP 工具接入**：让 Agent 通过 MCP 连接外部工具/服务，替换/扩展 `DemoTools`
  - 验收：模型可调用一个外部 MCP 工具完成真实任务
- [ ] **异步任务治理**：`CompletableFuture.runAsync` 改为 `@Async` + 自定义线程池，或加任务表支持失败重投
  - 验收：并发提交 10 个 Goal 稳定执行，无线程池耗尽
- [ ] **数据库迁移工具**：Flyway 替代 `spring.sql.init` 管理 schema 演进
  - 验收：新增表结构变更通过迁移脚本自动应用
- [ ] **模型调用重试/限流**：Spring Retry / Resilience4j，模型失败自动重试，接口限流防刷
  - 验收：bad key 场景按策略重试后失败，限流返回 429

## 侧重点 · 提升工程深度

> 避免项目被归为「API 调用 + CRUD 的套壳 Demo」；优先做「收益最大 × 认可度最高」且能讲清「为什么」的改造。

- [ ] **并发 / 资源控制**：流式连接数限制、异步线程池隔离与参数化、模型调用超时兜底与熔断降级
  - 验收：并发提交多个流式请求稳定，无连接/线程池耗尽

## P2 · 工程化与可观测性

- [ ] **Actuator + 监控**：加 `spring-boot-starter-actuator`，暴露健康/指标端点，接 Prometheus + Grafana
  - 验收：`/actuator/health` 可用，指标可被 Prometheus 抓取
- [ ] **链路追踪**：Micrometer Tracing / OpenTelemetry，记录 LLM 调用耗时与 token 消耗
  - 验收：一次聊天请求的完整调用链在追踪系统可见
- [ ] **API 文档**：springdoc-openapi 自动生成 Swagger UI
  - 验收：`/swagger-ui.html` 可浏览所有接口
- [ ] **会话缓存**：`spring-boot-starter-data-redis` 缓存会话快照，降低 MySQL 压力
  - 验收：会话上下文命中 Redis，DB 读次数下降
- [ ] **Testcontainers 集成测试**：起真实 MySQL 环境跑集成测试
  - 验收：`mvn test` 在容器化 DB 上全绿
- [ ] **Mockito 单测**：为 service 层补单元测试
  - 验收：核心 service 方法均有单测覆盖

## P3 · 按需（看产品方向再上）

- [ ] **安全**：Spring Security + JWT 接口鉴权；API Key 走 KMS/Vault 管理
  - 验收：未带 token 的请求被拒绝
- [ ] **消息队列**：RabbitMQ / Kafka 异步派发 Goal，长任务解耦
  - 验收：提交 Goal 后立即返回，执行与消费异步解耦
- [ ] **前端 + EventSource**：Vue3/React 页面消费 SSE 实时展示，替代/补充 CLI
  - 验收：浏览器能看到打字机式流式回复
- [ ] **容器化交付**：Dockerfile + docker-compose（app + MySQL + Redis）一键启动
  - 验收：`docker compose up` 后完整可访问
- [ ] **CI/CD**：GitHub Actions / Gitee Go：编译 → 测试 → 构建镜像
  - 验收：push 后流水线全绿并产出镜像
- [ ] **WebSocket**：如需全双工交互（如任务进度推送）可扩展

***

# 二、已完成（存档）

## 两路径架构（2026-08 完成）

- [x] **请求接入 Harness 入口**：`ChatController`（`/api/chat` 同步、`/api/chat/stream` 响应式）统一入口。
- [x] **加载会话原始数据**：`SessionService.loadContext(sessionId)` 还原 `List<Message>`。
- [x] **执行上下文组装**：`ContextAssemblingAdvisor` 按 token 预算裁剪（保留 system + 最近 N 轮）、过滤噪声、规范 role 顺序；测试见 `ContextAssemblingAdvisorTest`。
- [x] **主 Agent 前置判断**：`RouteJudge`/`LlmRouteJudge` 输出 SIMPLE/COMPLEX 结构化决策；异常兜底 SIMPLE（宁可简单）；测试见 `LlmRouteJudgeTest`。
- [x] **路径 A 真流式**：`GeneralAssistantAgent.executeStreamReactive` 走 `.stream().content()` 逐 token 发射（实测 token 间隔 48~70ms 到达）。
- [x] **路径 B 多 Agent 编排**：`MultiAgentGraphAgent` 基于 `StateGraph`（lead 拆解上限 `MAX_SUBTASKS=4` → subtask-0..3 并行 → 聚合），已在 `ChatAgentConfig` 注册 bean，测试见 `MultiAgentGraphAgentTest` / `ChatServiceImplTest`。
- [x] **执行进度实时推送**：graph-core 生命周期钩子旁路捕获并行分支完成事件（before/after 配对过滤短路槽位、串行发射防丢事件、关闸在 merge 前防死锁）；`ProgressLine` 统一线协议 `\0stage\1detail`；SSE 事件 event/data 单元素成对输出、内容行换行转义保完整性；CLI 实时渲染 `[stage] detail`。
- [x] **两条路径统一出口与记忆写回**：SSE 出口一致；会话记忆经 `writeBackContext` 回写（进度行不计入摘要）。
- [x] **CLI 传输稳定性**：OkHttp readTimeout=15min / callTimeout=30min，长任务不再中途断连；接收端还原换行转义。
- [x] **常量抽离 enums**：跨类共享常量归集至 `enums` 包（如 `AgentConstants`）。

## 项目基础（Roadmap P0 及能力项）

- [x] **Goal 落库**：Goal 从内存 `ConcurrentMap` 迁移到 MySQL，重启不丢；提供目标历史查询接口
- [x] **清理遗留依赖**：移除未使用的 JLine、Hutool 及无用 dependencyManagement 条目
- [x] **清理遗留 SQL**：`schema.sql` 重写废弃 `goal` 表
- [x] **统一参数校验**：`spring-boot-starter-validation` 注解校验，非法请求返回统一 400 错误体
- [x] **全局异常处理**：`@RestControllerAdvice` 统一 `{code, message}` 响应
- [x] **单 agent 系统**：创建 agent 表并引入项目
- [x] **多模型接入**：`ChatAgentConfig` 按 agent 表装配多个 Agent（qwen-plus/max/turbo），新增 Agent = 注册一个 bean + agent 表一行
- [x] **多 Agent 编排（初版）**：主 Agent 判定 COMPLEX 路由到 `multi-agent` 完成 lead→并行→聚合闭环
- [x] **响应式流式改造**：流式端点已直接返回 `Flux<String>`（text/event-stream），DB 阻塞操作经 `Schedulers.boundedElastic()` 边界隔离；同步 `/api/chat` 保留
- [x] **会话上下文管理与 Token 裁剪**：`ContextAssemblingAdvisor` 按 token 预算裁剪，长对话体积受控
- [x] **Mockito 单测（核心场景）**：10 个测试类 / 50 用例覆盖路由判定、双路径执行、进度协议、SSE 契约（详见 docs/functional-testing.md）

***

## 备注

- 测试全景见 [docs/functional-testing.md](./docs/functional-testing.md)，数据流详解见 [docs/data-flow.md](./docs/data-flow.md)，技术栈对照见 [TECH_STACK.md](./TECH_STACK.md)。
- 每项完成后按对应"验收"标准验证后再勾选。
