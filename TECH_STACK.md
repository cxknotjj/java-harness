# javaHarness 技术栈整理

> 基于 Spring AI 的 Agent 编排框架 Demo。本文档梳理项目现有技术栈，并给出可扩展方向。

***

## 一、项目现状

- **定位**：两路径 AI Agent 编排框架 —— 主 Agent（`RouteJudge` LLM 决策）按复杂度分流：简单请求走单次调用（路径 A），复杂请求走 StateGraph 多 Agent 编排（路径 B）；执行目标（Goal）全生命周期（PENDING → RUNNING → SUCCEEDED/FAILED）落库可追溯。
- **入口**：REST API（`/api/chat`、`/api/chat/stream`、`/api/harness/*`）+ CLI 聊天客户端（`mvn exec:java`）。
- **会话记忆**：MySQL 两张表（`session` + `session_messages`，一对一），`session_messages.content` 以 JSON 快照存储完整上下文。
- **目标存储**：`Goal` 持久化到 MySQL `goal` 表，重启不丢，可追溯历史。
- **执行反馈**：路径 A 逐 token 流式；路径 B 各阶段进度经生命周期钩子实时推送（`event: progress` SSE）。
- **工具执行**：容器级沙箱（agentscope-runtime）——模型生成的 Python/Shell 与浏览器操作只在 Docker 容器内执行，宿主机零暴露；按专家分配工具（服务端硬边界，未分配的工具对模型不可见）。

***

## 二、当前技术栈

| 分类       | 技术                         | 版本     | 用途 / 说明                                                                        |
| -------- | -------------------------- | ------ | ------------------------------------------------------------------------------ |
| 语言       | Java                       | 17     | JDK 版本（`pom.xml` release 17）                                                   |
| 构建       | Maven                      | -      | 依赖与构建管理，本地仓库重定向 `.mvn-repo`                                                    |
| 核心框架     | Spring Boot                | 3.5.14 | Web 应用框架（**硬约束：与 Spring AI 1.1.4 兼容**）                                         |
| AI 框架    | Spring AI                  | 1.1.4  | `spring-ai-starter-model-openai`，以 OpenAI 兼容模式对接通义千问 DashScope（qwen3.7-plus）   |
| Graph 编排  | spring-ai-alibaba-graph-core | BOM 1.1.2.2 | StateGraph 多 Agent 编排（lead→并行子任务→聚合）+ `GraphLifecycleListener` 进度旁路（路径 B） |
| 沙箱工具  | spring-ai-alibaba-sandbox | BOM 1.1.2.2 | 容器级工具隔离（传递 `agentscope-runtime-sandbox-core:1.0.2`）：base 容器（Python/Shell/文件读写检索）+ browser 容器（导航/快照/点击/输入），Docker 硬依赖 |
| Web      | Spring MVC                 | 随 Boot | REST + 响应式 SSE（Controller 返回 `Flux<String>`，`text/event-stream`）+ 全局异常处理 |
| ORM      | MyBatis-Plus               | 3.5.7  | `mybatis-plus-spring-boot3-starter`，goal / session / session\_messages / agent 表的 CRUD |
| 数据库      | MySQL                      | 9.2.0  | `mysql-connector-j`（runtime），连接池 HikariCP                                      |
| 参数校验     | Jakarta Validation         | 随 Boot | `spring-boot-starter-validation` + `@Valid` 注解校验                               |
| HTTP 客户端 | OkHttp                     | 4.12.0 | CLI 端调用主服务 REST/SSE（`cli/api/ChatApiClient`）                                   |
| 序列化      | Jackson                    | 随 Boot | DTO 序列化 / SSE meta 解析                                                          |
| 代码生成     | Lombok                     | -      | 实体类简化                                                                          |
| 测试       | JUnit 5 / Mockito          | 随 Boot | 11 个测试类 / 75 用例（不依赖真实 DB/网络，见 docs/functional-testing.md）                     |
| 日志       | SLF4J + Logback            | 随 Boot | Agent 执行状态日志                                                                   |
| 初始化 SQL  | schema.sql                 | -      | 启动时 `spring.sql.init` 建表                                                       |

### 架构分层

```
controller（REST + SSE 流式 + 全局异常处理）
  └─ service（接口 + impl：AgentService / ChatService / GoalService / SessionService
             ├─ RouteJudge + LlmRouteJudge（主 Agent 分流判定）
             └─ AgentConfigProvider（agent 表运行配置）
        ├─ agent（Agent 抽象 + GeneralAssistantAgent 路径A + MultiAgentGraphAgent 路径B
        │     + AgentChatCaller 调用封装 + BranchProgressListener 钩子旁路）
        │     └─ ProgressLine（进度行线协议，服务端↔CLI 共用）
        ├─ advisor（ContextAssemblingAdvisor：上下文组装/token 裁剪）
        ├─ tool（WebTools 网页抓取 / DemoTools 示例 / SandboxToolProvider 容器沙箱 / ToolAssignments 工具分配）
        ├─ cli（ChatCli 交互端） + cli/api（ChatApiClient：OkHttp + SSE 解析）
        ├─ domain（领域模型父包，含 dto 与 entity；RouteDecision 分流决策枚举）
        ├─ mapper（Agent / Goal / Session / SessionMessage / ModelProvider）
        ├─ enums（GoalStatus / AgentConstants）
        └─ 配置（application.yaml + config/agent：ChatAgentConfig、ChatClientFactory、ChatClientRegistry）
```

### 关键设计

- **两路径分流**：`LlmRouteJudge` LLM 决策 SIMPLE/COMPLEX（异常兜底 SIMPLE 宁可简单）；`ChatServiceImpl.resolveAgent()` 据此路由 `general` 或 `multi-agent`。
- **流式聊天**：`/api/chat/stream` 返回 `Flux<String>`（SSE）；每个事件单元素成对输出——内容 token 为普通 `data:` 行，进度为 `event: progress` + `{stage, detail}` JSON，结束发 `[DONE]` + `meta`（sessionId/goalId/status/error）。
- **逐 token 推送**：路径 A `GeneralAssistantAgent.executeStreamReactive` 走 `.stream().content()` 真流式边收边发；路径 B 子任务完成事件由 graph-core 生命周期钩子旁路捕获后并入主流（before/after 过滤短路槽位、串行发射防丢事件）。
- **Agent 路由**：请求显式携带 `agentId` 时优先按 agent 表映射路由（CLI `/agent <id>` 手动指定），否则按分流结果选择执行体；未命中回退 `general`。
- **动态配置**：每次调用经 `AgentService.getAgentConfig(agent_name)` 读 agent 表的 `model / prompt`，改库即时生效，无需重启。
- **会话写回统一**：同步 / 流式路径都在 `ChatServiceImpl` 统一写回会话记忆（进度行不计入摘要）。
- **工具调用（容器级沙箱）**：`SandboxToolProvider` 懒初始化 agentscope 沙箱（base 容器：Python/Shell/文件读写检索；browser 容器：导航/快照/点击/输入，独立镜像、独立初始化失败只降级本组），宿主机零暴露；`ToolAssignments` 按专家双通道注入（@Tool 对象走 `.tools()`、ToolCallback 走 `.toolCallbacks()`），未分配的工具对模型不可见（服务端硬边界）。researcher/general 拥有浏览器组，补 JS 渲染页面抓取空缺。
- **统一异常响应**：`GlobalExceptionHandler` 把所有异常转成 `{code, message}` 结构（400/404/500），非法请求返回统一 400 错误体。

***

## 三、可扩展技术栈建议

### 1. AI 与模型层（优先级：高）

| 建议            | 说明                                                                              |
| ------------- | ------------------------------------------------------------------------------- |
| 更多模型接入        | 官方 DeepSeek starter、本地 Ollama、Qwen 官方 DashScope starter，按 Agent 路由不同模型          |
| 多模态           | 图片/语音输入（DashScope 兼容模式逐步支持）                                                     |
| **RAG（向量检索）** | 引入向量库（pgvector / Redis Stack / Elasticsearch），结合 Spring AI `VectorStore` 做知识库问答 |
| **MCP 接入**    | 让 Agent 通过 MCP 连接外部工具/服务，扩展工具生态                                                 |
| Agent 编排框架    | LangGraph4j / AutoGen 风格的多 Agent 协作、任务规划、反思循环                                   |

### 2. 数据与持久化（优先级：高）

| 建议                    | 说明                                                |
| --------------------- | ------------------------------------------------- |
| **数据库迁移工具**           | Flyway / Liquibase 替代 `schema.sql`，管理 schema 演进   |
| PostgreSQL + pgvector | 若做 RAG，pgvector 是现成方案                             |
| 缓存                    | `spring-boot-starter-data-redis`：会话快照缓存、限流计数、热点数据 |

### 3. 服务治理与可靠性（优先级：中）

| 建议                | 说明                                                                |
| ----------------- | ----------------------------------------------------------------- |
| **Actuator + 监控** | `spring-boot-starter-actuator`：健康检查、指标；可接 Prometheus + Grafana    |
| 可观测性              | Micrometer Tracing / OpenTelemetry：链路追踪（LLM 调用耗时、token 消耗）        |
| 重试与熔断             | Spring Retry / Resilience4j：模型调用失败重试、限流熔断                         |
| 消息队列              | RabbitMQ / Kafka：异步 Goal 派发、长任务解耦、失败重投                            |
| 异步任务治理            | 当前 `CompletableFuture.runAsync` 无线程池管理，可换 `@Async` + 自定义线程池 / 任务表 |

### 4. API 与接口规范（优先级：中）

| 建议     | 说明                                                                        |
| ------ | ------------------------------------------------------------------------- |
| API 文档 | springdoc-openapi（Swagger UI / OpenAPI 3）                                 |
| DTO 映射 | MapStruct 替代手写 VO 转换                                                      |

### 5. 安全（优先级：视需求）

| 建议                    | 说明                                          |
| --------------------- | ------------------------------------------- |
| Spring Security + JWT | 接口鉴权、多用户隔离                                  |
| API Key 管理            | 密钥不落代码/配置，走密钥管理服务（Vault / KMS），支持多租户 Key 路由 |

### 6. 测试与质量（优先级：中）

| 建议             | 说明                                           |
| -------------- | -------------------------------------------- |
| Testcontainers | 集成测试起 MySQL/向量库真实环境                          |
| Mockito 补全     | service 层核心场景已有 50 用例，继续补长尾分支与异常路径           |
| 契约测试           | 对 `/api/chat/stream` SSE 格式做契约测试，防止客户端/服务端漂移 |

### 7. 工程化与交付（优先级：低-中）

| 建议        | 说明                                                    |
| --------- | ----------------------------------------------------- |
| 容器化       | Dockerfile + docker-compose（app + MySQL + Redis），一键启动 |
| CI/CD     | GitHub Actions / Gitee Go：编译、测试、镜像构建                  |
| 前端        | Vue3/React + EventSource 消费 SSE，替代/补充 CLI             |
| WebSocket | 若需全双工交互可扩展                                            |

***

## 四、注意事项

- **版本约束**：Spring Boot 必须为 3.5.14（与 Spring AI 1.1.4 兼容），升级需整体评估 Spring AI 兼容性。
- **模型名**：`application.yaml` 中 `model: qwen3.7-plus`，如需切换模型请同步核对 DashScope 可用模型。
- **DB 迁移**：`goal` 表结构升级过，若在更旧库上运行需先 `DROP TABLE goal` 让 schema.sql 重建。
- **Docker 硬依赖**：沙箱工具依赖本机 Docker；两个镜像需预拉取（aliyun 新加坡 registry）：`runtime-sandbox-base:latest`（约 1.4GB）与 `runtime-sandbox-browser:latest`（约 2GB），否则首个工具调用会现场拉镜像拖慢执行。无 Docker 时沙箱工具组为空（warn 日志），其余功能不受影响。过程记录见 [docs/0828-沙箱接入与验证.md](./docs/0828-沙箱接入与验证.md)。
- **容器生命周期**：容器随首次工具调用懒创建、服务优雅停止时随 `@PreDestroy` 销毁；强杀 `mvn spring-boot:run` 不触发优雅关闭会残留容器，需正常停服或 `docker rm -f` 清理。

