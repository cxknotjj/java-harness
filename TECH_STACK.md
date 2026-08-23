# javaHarness 技术栈整理

> 基于 Spring AI 的 Agent 编排框架 Demo。本文档梳理项目现有技术栈，并给出可扩展方向。

---

## 一、项目现状

- **定位**：Agent 编排框架 —— 通过 `Agent` 抽象路由到不同实现（当前为通用 AI 助手），执行目标（Goal）并维护其生命周期（PENDING → RUNNING → SUCCEEDED/FAILED）。
- **入口**：REST API（`/api/chat`、`/api/chat/stream`、`/api/harness/*`）+ CLI 聊天客户端（`mvn exec:java`）。
- **会话记忆**：MySQL 两张表（`session` + `session_messages`，一对一），`session_messages.content` 以 JSON 快照存储完整上下文。
- **目标存储**：`Goal` 当前为**内存存储**（`ConcurrentMap`），未落库。

---

## 二、当前技术栈

| 分类 | 技术 | 版本 | 用途 / 说明 |
|------|------|------|-------------|
| 语言 | Java | 17 | JDK 版本（`pom.xml` release 17） |
| 构建 | Maven | - | 依赖与构建管理，本地仓库重定向 `.mvn-repo` |
| 核心框架 | Spring Boot | 3.5.14 | Web 应用框架（**硬约束：与 Spring AI 1.1.4 兼容**） |
| AI 框架 | Spring AI | 1.1.4 | `spring-ai-starter-model-openai`，以 OpenAI 兼容模式对接通义千问 DashScope（qwen3.7-plus） |
| Web | Spring MVC | 随 Boot | REST + SSE（`SseEmitter` 流式推送）+ 全局异常处理（`@RestControllerAdvice`） |
| ORM | MyBatis-Plus | 3.5.7 | `mybatis-plus-spring-boot3-starter`，session 两张表的 CRUD |
| 数据库 | MySQL | 9.2.0 | `mysql-connector-j`（runtime），连接池 HikariCP |
| HTTP 客户端 | OkHttp | 4.12.0 | CLI 端调用主服务 REST/SSE（`cli/api/ChatApiClient`） |
| 序列化 | Jackson | 随 Boot | DTO 序列化 / SSE meta 解析 |
| 代码生成 | Lombok | - | 实体类简化 |
| 测试 | JUnit 5 / Spring Boot Test | 随 Boot | 上下文加载测试 |
| 日志 | SLF4J + Logback | 随 Boot | Agent 执行状态日志 |
| 初始化 SQL | schema.sql | - | 启动时 `spring.sql.init` 建表 |
| 其他 | JLine 3.29.0 / Hutool 5.8.32 | - | 已声明但**当前未使用**（遗留） |

### 架构分层

```
controller（纯转发 + 全局异常处理）
  └─ service（接口 + impl：AgentService / ChatService / GoalService / SessionService）
        ├─ agent（Agent 抽象 + GeneralAssistantAgent）
        ├─ tool（DemoTools：@Tool 工具调用）
        ├─ cli（ChatCli 交互端） + cli/api（ChatApiClient）
        ├─ domain（Goal 内存模型）
        ├─ entity / mapper（session、session_messages）
        ├─ enums / dto
        └─ 配置（application.yaml）
```

### 关键设计

- **流式聊天**：`/api/chat/stream` 走 SSE，逐 token 推送，结束发 `[DONE]` + `meta`（sessionId/goalId/status/error）。
- **会话写回统一**：同步 / 流式路径都在 `ChatServiceImpl` 统一写回会话记忆。
- **工具调用**：`ChatClient.defaultTools(new DemoTools())` 注册，模型可调用时间/计算/天气等工具。
- **统一异常响应**：`GlobalExceptionHandler` 把所有异常转成 `{code, message}` 结构（400/404/500）。

---

## 三、可扩展技术栈建议

### 1. AI 与模型层（优先级：高）

| 建议 | 说明 |
|------|------|
| 更多模型接入 | 官方 DeepSeek starter、本地 Ollama、Qwen 官方 DashScope starter，按 Agent 路由不同模型 |
| 多模态 | 图片/语音输入（DashScope 兼容模式逐步支持） |
| **RAG（向量检索）** | 引入向量库（pgvector / Redis Stack / Elasticsearch），结合 Spring AI `VectorStore` 做知识库问答 |
| **MCP 接入** | 让 Agent 通过 MCP 连接外部工具/服务，扩展工具生态 |
| Agent 编排框架 | LangGraph4j / AutoGen 风格的多 Agent 协作、任务规划、反思循环 |

### 2. 数据与持久化（优先级：高）

| 建议 | 说明 |
|------|------|
| **Goal 落库** | 当前 Goal 为内存存储，重启即丢；建议落库（MySQL 或转 PostgreSQL）以支持任务追溯 |
| **数据库迁移工具** | Flyway / Liquibase 替代 `schema.sql`，管理 schema 演进 |
| PostgreSQL + pgvector | 数据库已声明管理但未使用；若做 RAG，pgvector 是现成方案 |
| 缓存 | `spring-boot-starter-data-redis`：会话快照缓存、限流计数、热点数据 |

### 3. 服务治理与可靠性（优先级：中）

| 建议 | 说明 |
|------|------|
| **Actuator + 监控** | `spring-boot-starter-actuator`：健康检查、指标；可接 Prometheus + Grafana |
| 可观测性 | Micrometer Tracing / OpenTelemetry：链路追踪（LLM 调用耗时、token 消耗） |
| 重试与熔断 | Spring Retry / Resilience4j：模型调用失败重试、限流熔断 |
| 消息队列 | RabbitMQ / Kafka：异步 Goal 派发、长任务解耦、失败重投 |
| 异步任务治理 | 当前 `CompletableFuture.runAsync` 无线程池管理，可换 `@Async` + 自定义线程池 / 任务表 |

### 4. API 与接口规范（优先级：中）

| 建议 | 说明 |
|------|------|
| API 文档 | springdoc-openapi（Swagger UI / OpenAPI 3） |
| 参数校验 | `spring-boot-starter-validation`（jakarta validation），替换手写 `message 非空` 校验 |
| DTO 映射 | MapStruct 替代手写 VO 转换 |

### 5. 安全（优先级：视需求）

| 建议 | 说明 |
|------|------|
| Spring Security + JWT | 接口鉴权、多用户隔离 |
| API Key 管理 | 密钥不落代码/配置，走密钥管理服务（Vault / KMS），支持多租户 Key 路由 |

### 6. 测试与质量（优先级：中）

| 建议 | 说明 |
|------|------|
| Testcontainers | 集成测试起 MySQL/向量库真实环境 |
| Mockito | 单元测试 service 层（当前仅上下文加载测试） |
| 契约测试 | 对 `/api/chat/stream` SSE 格式做契约测试，防止客户端/服务端漂移 |

### 7. 工程化与交付（优先级：低-中）

| 建议 | 说明 |
|------|------|
| 容器化 | Dockerfile + docker-compose（app + MySQL + Redis），一键启动 |
| CI/CD | GitHub Actions / Gitee Go：编译、测试、镜像构建 |
| 前端 | Vue3/React + EventSource 消费 SSE，替代/补充 CLI |
| WebSocket | 若需全双工交互可扩展 |

---

## 四、注意事项

- **版本约束**：Spring Boot 必须为 3.5.14（与 Spring AI 1.1.4 兼容），升级需整体评估 Spring AI 兼容性。
- **遗留清理**：JLine、Hutool 依赖未使用；`schema.sql` 中 `goal` 表为旧版残留（当前 Goal 内存存储）。扩展前建议先清理。
- **模型名**：`application.yaml` 中 `model: qwen3.7-plus`，如需切换模型请同步核对 DashScope 可用模型。
