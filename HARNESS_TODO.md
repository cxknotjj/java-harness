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

| 目标架构角色                 | 现有实现                                              |
| ---------------------- | ------------------------------------------------- |
| Harness 外壳入口           | `ChatController` / `ChatService` / `AgentService` |
| 会话原始数据加载               | `SessionService.loadContext(sessionId)`           |
| 上下文组装(过滤/截断)           | `ContextAssemblingAdvisor`                        |
| 主 Agent 前置判断           | `RouteJudge` / `LlmRouteJudge`                    |
| 路径 A(简单) 单次调用          | `GeneralAssistantAgent`                           |
| 路径 B(复杂) 多 Agent Graph | `MultiAgentGraphAgent`（lead→并行子任务→聚合→final）       |
| 统一响应出口                 | `ChatController` 同步 + 响应式 SSE                     |
| 兼容兜底                   | `AgentService` 回退 general                         |

***

# 一、未完成 TODO

## P0 · 架构收尾（眼前优先）

- [x] **补齐 agent 表配置**：`schema.sql` 种子数据新增 `agentName='multi-agent'` 行（qwen-max + 编排提示词），重启即生效（`INSERT IGNORE` 幂等），消除「使用默认配置」退回。
- [x] **专家 agent 派遣**：lead 拆解 JSON 升级为对象数组 `{"subtasks":[{"desc":"..","agent":"专家名"}]}`，subtask 节点按专家名查 agent 表配置取对应客户端执行；白名单校验（researcher/coder/analyst/writer/general），非法回退默认；兼容旧纯字符串格式。测试见 `MultiAgentGraphAgentTest` 派遣与回退两用例。
  - **专家清单（按现有流程 lead→并行子任务→聚合 设计，登记进 agent 表）**：
    | agent\_name   | 职责                  | 建议 model      | system prompt 要点                              | 依赖工具（P1 工具库） |
    | ------------- | ------------------- | ------------- | --------------------------------------------- | ------------ |
    | `multi-agent` | 编排器本体（lead 拆解 + 聚合） | qwen-max      | 规划者：把复杂目标拆成 ≤4 条可独立执行、可判定完成度的子任务；聚合时忠实汇总不改写结论 | 无（纯规划）       |
    | `researcher`  | 资料调研                | qwen-plus     | 检索信息、交叉核对来源、输出带出处的资料摘要                        | 网页搜索 + 抓取    |
    | `coder`       | 代码编写/修复             | deepseek-chat | 读懂仓库上下文、给出可运行代码与修改说明、执行验证命令                   | 文件读写 + Shell |
    | `analyst`     | 数据分析                | deepseek-chat | 结构化数据处理、指标计算、结论用数字说话                          | SQL 只读 + 计算  |
    | `writer`      | 汇总撰写                | qwen-max      | 把多方结果整合成结构化报告（标题/要点/结论），忠实引用子任务产出             | 无            |
    | `general`     | 通用兜底（已存在）           | 现配置           | 通用助手，未匹配到专家时由 lead 回退指派                       | 无            |
  - 落地顺序：先插 `multi-agent` 行解 P0 配置退回 → 再建专家行（prompt 可先简配）→ 最后打通 lead 按 `agentId` 派遣；未识别的 agentId 一律回退 `general`。
- [ ] **Graph 内逐 token 流式**：复杂路径当前只做到「阶段进度实时推送 + 聚合结果一次性输出」，子任务节点仍是阻塞单次调用；演进为 Graph 各节点产出逐 token 流。
- [ ] **并发断连的可观测处理**：多 Agent 并发执行期间若客户端断开（超时/退出），Tomcat 会报 `AsyncRequestNotUsableException`（Connection reset by peer）。根因（CLI 超时过短、伪流式等待）已修复，服务端还需把「下游断开」降噪为 warn 并及时终止编排，避免无谓消耗。
- [ ] 为复杂路径注册 Checkpointer（可选，落库断点）——如需再评估。

## P1 · 能力增强（核心扩展）

- [ ] **RAG 知识库**：引入向量库（pgvector 优先，因其已在依赖管理中）+ Spring AI `VectorStore`
  - 验收：能对本地文档做"知识库问答"
- [ ] **MCP 工具接入**：让 Agent 通过 MCP 连接外部工具/服务，替换/扩展 `DemoTools`
  - 与 Sandbox 衔接：agentscope-runtime 内置 MCP 桥接，接入时优先评估复用（见「Spring AI Alibaba Sandbox 接入」条目）
  - 验收：模型可调用一个外部 MCP 工具完成真实任务
- [x] **Agent 工具库扩充**：`tool` 包落地真实工具体系（对标 DeepSeek Harness 内置工具面），按专家分配、统一治理（宿主机自研工具后被 Sandbox 容器级隔离替代并退役，见下一条）：
  - 现存自研工具：`WebTools`（fetchUrl 真实抓取，HTML→纯文本，仅 http/https，2MB/12k 字符上限）+ `DemoTools`（本地函数示例）；网页搜索待搜索服务商 API key 后补充
  - 已退役（能力由 Sandbox 等价承接）：`ToolSandbox`/`FileTools`/`SearchTools`/`ShellTools` 连同其治理与测试一并移除
  - `ToolAssignments`：按专家分配工具集，双通道注入（@Tool 对象走 `.tools()`、ToolCallback 走 `.toolCallbacks()`；`MultiAgentGraphAgent.call` 按子任务专家、`GeneralAssistantAgent` 按自身 agent 名；未指派子任务回退 general 全量）
  - 剩余：web search 接入搜索 API key；交互式高危命令审批通道
- [x] **Spring AI Alibaba Sandbox 接入**：引入 `spring-ai-alibaba-sandbox`（BOM 管理版本，传递 `agentscope-runtime-sandbox-core`），工具执行从进程内软隔离升级为容器级隔离。
  **架构决策**：产品定位为通用助手（调研/报告/数据分析/跑代码），agent 不操作宿主机项目文件——模型生成的所有代码/命令只在容器内执行，宿主机零暴露；不做降级路径（沙箱是硬依赖，部署前提标注 Docker 必需，无 Docker 环境该功能整体不可用）。
  - `SandboxToolProvider`：懒初始化（首次取用才拉起容器，双检锁只尝试一次），初始化失败降级为空工具面（warn 日志、不重试、不回退宿主机）；应用退出 `@PreDestroy` 释放 `SandboxService`（`close()` 随容器删除）
  - 工具面三类：执行类（RunPythonCode/RunShellCommand）、只读文件类（ReadFile/ReadMultipleFiles/ListDirectory/DirectoryTree/SearchFiles/GetFileInfo）、写入类（WriteFile/EditFile/CreateDirectory/MoveFile）
  - 重合即退役：`ShellTools`→`RunShellCommandTool`，`FileTools`/`SearchTools`/`ToolSandbox`→沙箱文件类工具；`WebTools`/`DemoTools` 保留（Sandbox 未覆盖）
  - `ToolAssignments` 双通道：Sandbox 工具为 `ToolCallback` 形态走 `.toolCallbacks()`，自研 `@Tool` 对象走 `.tools()`，按专家分配不变
  - Docker host 无需显式配置：默认 `localhost:2375` 失败后自动回退 Docker Desktop 标准 socket
  - 验证：75/75 单测通过；Docker 29.7.2 下 JShell 真实验证容器拉起 + 容器内 Shell 执行（returncode=0）+ 退出容器自动删除；镜像 `runtime-sandbox-base:latest` 需预拉取（aliyun 新加坡 registry）
- [x] **Sandbox 落地遗留跟进**（承接上一条，过程记录见 `docs/0828-沙箱接入与验证.md`）：
  - 真实 LLM 端到端 ✅：流式接口发「Python 计算前 20 个素数和并运行验证」复杂任务——lead 按难度拆 1 个子任务指派 coder，`RunPythonCodeTool` 经容器 fastapi `run_ipython_cell` 端点执行，SSE 进度完整（编排→拆解→子任务→聚合）、`meta SUCCEEDED`、结果正确（素数和 639）；容器随首次工具调用创建（懒初始化），服务优雅停止时随 `@PreDestroy` 销毁（注意：强杀 `mvn spring-boot:run` 不触发优雅关闭会残留容器，需正常停服或 `docker rm -f`）
  - 浏览器自动化 ✅：`SandboxToolProvider` 新增浏览器工具组（Navigate/Snapshot/Click/Type/Close，独立镜像 `runtime-sandbox-browser:latest` 需预拉取；独立懒初始化，失败只降级本组不影响执行/文件类）；分配给 researcher/general 补 JS 渲染页面抓取空缺；JShell 直连验证：`BrowserSandbox` navigate 到 `quotes.toscrape.com/js/`（纯 HTTP 抓取拿不到内容的 JS 渲染页）并 snapshot 取得 5381 字符无障碍快照（含 JS 加载的名言正文），关闭后容器删除零残留；`ToolAssignments` 分配表与测试同步更新，全量回归通过
- [ ] **工具分配最小权限化**：现状按「能力类别」粒度分配（`ToolAssignments` 给整组工具），存在权限漏洞：
  - **`general` 全量过宽且是所有回退路径的落点**：路由兜底、未识别专家、lead 漏指派全部落 general——最宽权限（执行/容器写/网页）给了最不可控的场景，违背最小权限原则
  - 改造项（沙箱语境下已简化：沙箱原生分执行/只读文件/写入三类，无需再拆类）：
    - `general` 收敛为只读探索者（fetchUrl + 沙箱只读文件/检索），执行与写入类工具只留给 lead 明确指派的 `coder`/`analyst`
    - 重排 `ToolAssignments` 分配表并同步测试（专家派遣用例、`ToolAssignments` 相关断言）
  - 机制说明：权限边界是**服务端硬边界**——只注入已分配工具的 schema，模型看不见未分配的工具，伪造调用在服务端无执行注册（比提示词约束可靠）
  - 权限模型即「哪些 agent 允许触发容器执行/容器写」
  - 验收：general/未指派子任务全链路无写与执行权限；coder 仍可完成「读文件→改文件→跑命令」闭环
- [ ] **异步任务治理**：`CompletableFuture.runAsync` 改为 `@Async` + 自定义线程池，或加任务表支持失败重投
  - 验收：并发提交 10 个 Goal 稳定执行，无线程池耗尽
- [ ] **数据库迁移工具**：Flyway 替代 `spring.sql.init` 管理 schema 演进
  - 验收：新增表结构变更通过迁移脚本自动应用
- [ ] **模型调用重试/限流**：Spring Retry / Resilience4j，模型失败自动重试，接口限流防刷
  - 验收：bad key 场景按策略重试后失败，限流返回 429
- [ ] **CLI 输出优化（对标 Claude Code 的终端体验）**：
  - 流式 Markdown 渲染：逐 token 渲染标题/列表/**粗体**/代码块语法高亮，而非纯文本直出
  - 过程状态原位刷新：`编排/拆解/子任务/聚合` 等进度用 spinner + 已耗时在固定状态行刷新，完成后折叠归档，不随内容滚动刷屏
  - 回合小结：回答结束后展示本回合统计（耗时、子任务数、token 近似量）
  - 选型提示：需要 ANSI 控制/按键处理时评估 JLine 3 / Lanterna（JLine 曾作为遗留依赖清理过，若引入需给出不可替代的理由）
  - 验收：复杂任务全程可视（进度非刷屏式），最终回答以排版后的 Markdown 呈现

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
- [x] **路径 A 真流式**：`GeneralAssistantAgent.executeStreamReactive` 走 `.stream().content()` 逐 token 发射（实测 token 间隔 48\~70ms 到达）。
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

- 测试全景见 [docs/functional-testing.md](./docs/functional-testing.md)，数据流详解见 [docs/data-flow.md](./docs/data-flow.md)，技术栈对照见 [TECH\_STACK.md](./TECH_STACK.md)。
- 每项完成后按对应"验收"标准验证后再勾选。

