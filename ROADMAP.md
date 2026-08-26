# javaHarness 扩展 Roadmap

> 项目后续可扩展方向的 TODO 清单（与 [TECH\_STACK.md](./TECH_STACK.md) 对应）。
> 约定：`- [ ]` 未开始，`- [x]` 已完成。按优先级排序，优先级高的先做。

***

## 一、P0 · 基础完善（建议优先）

- [x] **Goal 落库**：Goal 从内存 `ConcurrentMap` 迁移到 MySQL，重启不丢；提供目标历史查询接口
  - 验收：重启后 `/api/harness/goals` 仍能查到历史 Goal
- [x] **清理遗留依赖**：移除未使用的 JLine、Hutool，以及 dependencyManagement 中无用的 mysql-connector-java / pgvector 条目
  - 验收：`mvn dependency:analyze` 不再报告未用依赖（剩余为 Spring Boot starter 误报）
- [x] **清理遗留 SQL**：重写 `schema.sql` 中废弃的 `goal` 表，改为匹配落库后的 Goal 模型
  - 验收：启动日志无误导性建表
- [x] **统一参数校验**：引入 `spring-boot-starter-validation`，用注解校验替换手写 `message 非空` 判断
  - 验收：非法请求返回统一 400 错误体
- [x] **全局异常处理**：`@RestControllerAdvice` 统一异常响应结构
  - 验收：所有异常返回 `{code, message}` 格式
- [x] **单agent系统**：`创建agent表并且将其引入项目中`

## 二、P1 · 能力增强（核心扩展）

- [x] **多模型接入**：接入官方 DeepSeek / 本地 Ollama / Qwen 官方 starter，Agent 按名称路由到不同模型
  - 验收：新增 Agent 实现即可切换模型，无需改 ChatService
  - 现状：`GeneralAssistantAgent` 已参数化，`ChatAgentConfig` 装配 general / writer / coder 多个 Agent，每个绑定一个 agent_name，模型从 agent 表读取（qwen-plus / qwen-max / qwen-turbo）；新增 Agent = 注册一个 bean + agent 表一行
- [ ] **RAG 知识库**：引入向量库（pgvector 优先，因其已在依赖管理中）+ Spring AI `VectorStore`
  - 验收：能对本地文档做"知识库问答"
- [ ] **MCP 工具接入**：让 Agent 通过 MCP 连接外部工具/服务，替换/扩展 `DemoTools`
  - 验收：模型可调用一个外部 MCP 工具完成真实任务
- [ ] **多 Agent 编排**：引入 Spring AI Alibaba 或自研规划-执行循环（Planner + Executor）
  - 验收：一个复杂目标被拆分为多个子任务并由子 Agent 执行
- [ ] **异步任务治理**：`CompletableFuture.runAsync` 改为 `@Async` + 自定义线程池，或加任务表支持失败重投
  - 验收：并发提交 10 个 Goal 稳定执行，无线程池耗尽
- [ ] **数据库迁移工具**：Flyway 替代 `spring.sql.init` 管理 schema 演进
  - 验收：新增表结构变更通过迁移脚本自动应用
- [ ] **模型调用重试/限流**：Spring Retry / Resilience4j，模型失败自动重试，接口限流防刷
  - 验收：bad key 场景按策略重试后失败，限流返回 429

* [ ] **沙箱功能**：基于docker实现沙箱，锁定代码的执行空间，防止agent误操作本机系统。

## 三、P2 · 工程化与可观测性

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
- [ ] **Mockito 单测**：为 service 层补单元测试（当前仅上下文加载测试）
  - 验收：核心 service 方法均有单测覆盖

## 四、P3 · 按需（看产品方向再上）

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

## 备注

- 版本强约束：Spring Boot 保持 3.5.14（兼容 Spring AI 1.1.4），升级任何 AI 相关依赖前先验证兼容性。
- 建议按 P0 → P1 → P2 顺序推进，P3 视实际产品需求决定。
- 每项完成后按对应"验收"标准验证后再勾选。

