# javaHarness Roadmap & TODO

> 由原 `ROADMAP.md` 与 `HARNESS_TODO.md` 合并而成。
> 约定：`- [ ]` 未开始，`- [x]` 已完成。**未完成在前（按优先级），已完成在后（存档备查）**。
> 版本强约束：Spring Boot 保持 3.5.14（兼容 Spring AI 1.1.4），升级任何 AI 相关依赖前先验证兼容性。
> 首次可用性约定：新增功能/入口合入前按 `docs/first-run-audit.md`「五、首次使用流程规则」复查，走查结论追加至该报告「六、走查记录」。

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

## 方向评估（2026-08-28）

**现状盘点（已具备）**：两路径架构闭环（RouteJudge 分流 + StateGraph 多 Agent 编排）；沙箱容器级隔离 + 按专家分配工具权限（服务端硬边界）；进度全链路可视（编排/拆解/子任务/聚合/工具行）+ 断连优雅处理（降噪/取消传播/编排短路）；CLI 体验对标 Claude Code（流式 Markdown / spinner / 回合小结 / JLine 输入）；101 用例单测覆盖核心链路。单用户场景完整跑通。

**短板（按风险排序）**：

1. **可靠性弱**：LLM 调用失败即整体失败（无重试/降级/熔断）；无并发上限，多请求可打爆线程池与模型配额——demo 可跑，多人不可用
   （2026-09 部分收口：LLM 调用已有 `LlmRetry` 三次指数退避；后台 Goal 已有受管线程池容量上限与拒绝兜底，见存档「异步治理与发布工程」——限流/熔断/降级仍缺）
2. **能力面窄**：无 web search（查资料仅 fetchUrl+浏览器硬抓）；无 RAG（不能知识库问答）；无 MCP（工具生态扩展受限）——「调研/报告」核心卖点被卡在信息获取第一步
3. **编排智能化初级**：lead 一次性拆解、子任务纯并行无依赖、失败无重规划/反思——复杂任务成功率受限
4. **可观测空白**：LLM 调用耗时 / token 成本 / 链路不可见，调优靠日志肉翻
5. **部署与安全**：无容器化交付、无接口鉴权

**扩展方向定调**：

- **主线 = 能力扩展**（web search → RAG → MCP）：直接抬高「调研型助手」能力上限，产品价值最直观
- **支线 = 可靠性收口**（重试/限流/并发控制/最小权限）：demo → 可服务的门槛，量小优先做（P0）
- **中期 = 编排智能化**（失败重规划 / 子任务依赖）：能力面铺完后收益最大，agent 从「能跑」到「聪明」
- **远期 = 工程化可观测 + 产品化**（监控/追踪/容器化/前端）：按需启动

**发展方向增补（2026-09-06 评估）**：

- **Goal 失败重投任务化**（新增至 P2）：线程池治理完成后可靠性补全的下一块；设计要点已评估（路由列迁移 + 瞬态失败限定 + 防双跑），详见 P2 条目
- **多实例水平扩展**（新增至 P3）：解除单实例假设——`failAllRunning` 启动清理、本地线程池、GRAPH_CHECKPOINT 单写者；与「消息队列」条目合并推进
- **长期记忆滚动摘要**（新增至 P2）：当前历史为全量快照 + 预算裁剪（最旧轮次直接丢弃），超长会话会丢单
- **Agent 行为回归评测集**（新增至 P3）：prompt / 模型参数改动后防回归的固定评测集，与 `llm_call_log` 成本账本互补
- **多模态输入**（远期备选）：视觉模型（qwen-vl 类）经 agent 表接入，待真实需求再启动

## P0 · 可靠性与安全收口（眼前优先，先做稳）

- [ ] **模型调用重试/限流**：Spring Retry / Resilience4j，模型失败自动重试（指数退避，可重试错误与限流错误区分），接口限流防刷
  - 验收：bad key 场景按策略重试后失败；限流返回 429
- [ ] **并发 / 资源控制**：流式连接数限制、模型调用超时兜底与熔断降级（COMPLEX 编排失败可降级为 SIMPLE 单模型重答）
  - 2026-09 部分完成：异步线程池隔离与参数化已落地（`goalExecutor` 有界池 + 拒绝兜底 + MVC 异步槽位，见存档「异步治理与发布工程」）；连接数限制与熔断降级待做
  - 验收：并发提交多个流式请求稳定，无连接/线程池耗尽（线程池部分已由 10 并发 submit 用例覆盖）
- [x] **Token 预算统一控制**（2026-09-08 落地，详见 `docs/guides/context-optimization.md` 第五节）：预算集中配置（`app.context.*` / `ContextBudgetProperties`），消费侧新增两项——
  - 输出封顶（生成侧防失控，非事后裁剪）：`AgentChatCaller.buildSpec` 按角色分档设 `maxTokens`——lead 2000（JSON 中间产物）/ 聚合与路径 A 直出 8000（同直出最终回答共档）/ 子任务专家 4000；0 = 不限保持模型默认
  - 编排全程消费上限熔断（`orchestration-budget` 60000）：input 注入内存账本（AtomicLong + 估算标记），`AgentChatCaller` 经 `BudgetLedger` 按 roundtrip 增量同步累计，发起调用前与每轮 usage 帧两时点熔断（含单次 call 内部工具循环每轮，超限断流；真实 usage 优先，无则估算并与 `tokens_estimated` 同口径，不依赖异步落库时序）；未发起子任务超限即短路跳过（零 LLM 调用，result 写「预算超限跳过」占位），已在途的在下一轮熔断停止；聚合必发不受熔断（record-only 仅记账）、prompt 前置【预算降级说明】（跳过数 + 已消耗/上限 + 口径），编排正常终态；子任务并行限并发 `subtask-concurrency` 错峰，收窄在途超额窗口（2026-09-08 评审 C1 按方向 b 修正：熔断下沉 caller roundtrip 粒度，消除并行扇出检查盲区与工具循环烧穿）
  - 会话累计不设硬配额（评估结论：输入/输出双侧已封顶单次消耗，会话累计只反映正常用量，硬拦截伤聊天体验且无安全收益），消费量观测由 `llm_call_log` 与 `GET /api/llm-calls` 承担
  - 验收 ✅：熔断用例（同步 + 流式 + 真实部分跳过）子任务 mock 零交互、聚合带降级说明正常返回；三档 maxTokens 捕获 ChatOptions 断言、0 时不写入；caller 门控/记账 + 配置绑定用例（共新增 17 用例）
  - 遗留（P1）：子任务结果喂聚合前按预算分摊裁剪，前移聚合侧 sections 兜底——避免输出都长时靠后子任务整段被剪
- [ ] **工具分配最小权限化**：现状按「能力类别」粒度分配（`ToolAssignments` 给整组工具），存在权限漏洞：
  - **`general`** **全量过宽且是所有回退路径的落点**：路由兜底、未识别专家、lead 漏指派全部落 general——最宽权限（执行/容器写/网页）给了最不可控的场景，违背最小权限原则
  - 改造项（沙箱语境下已简化：沙箱原生分执行/只读文件/写入三类，无需再拆类）：
    - `general` 收敛为只读探索者（fetchUrl + 沙箱只读文件/检索 + 浏览器），执行与写入类工具只留给 lead 明确指派的 `coder`/`analyst`
    - 重排 `ToolAssignments` 分配表并同步测试（专家派遣用例、`ToolAssignments` 相关断言）
  - 机制说明：权限边界是**服务端硬边界**——只注入已分配工具的 schema，模型看不见未分配的工具，伪造调用在服务端无执行注册（比提示词约束可靠）
  - 验收：general/未指派子任务全链路无写与执行权限；coder 仍可完成「读文件→改文件→跑命令」闭环
- [x] **异步任务治理**：`CompletableFuture.runAsync` 改为受管线程池（`@Async` 注解因自调用陷阱弃用，直接注入 Executor）
  - 落地：`GoalExecutorConfig` 双池（`goal-exec-` 后台 Goal 池 core=max=8 / 队列 50 / 优雅停机；`applicationTaskExecutor` MVC 异步槽位）；队列满拒绝 → Goal 落 FAILED 终态；`run()` catch Throwable 堵 Error 逃逸卡 RUNNING
  - 验收 ✅：并发提交 10 个 Goal 稳定执行全 SUCCEEDED、无拒绝无卡 RUNNING（单测覆盖）；失败重投部分经评估另立任务（见 P2「Goal 失败重投」），详见存档「异步治理与发布工程」

- [x] **工具调用与 MCP 日志记录**：现状仅 `ToolCallTracer` 装饰 ToolCallback 发 `tool`/`tool-done` SSE 进度行（无 emitter 的调用链路不可见），工具调用不落库——`llm_call_log` 只记 LLM 调用，工具侧无成本/审计账本；MCP server 连接/发现失败仅 warn 单行，无 server 维度可查询记录
  - 改造要点：仿照 `llm_call_log` 落库工具调用（工具名/参数摘要/耗时/成败/所属 agent 与 sessionId），MCP 工具标注来源 server；MCP 连接失败、懒连接失败结构化记录（`McpToolProvider` 每 server 独立记录，失败隔离后仍可追溯）
  - 落地：V15 `tool_call_log` + V16 `mcp_server_log` 两张观测表；`LlmCallRecorder` 扩展 `recordToolCall`（两类台账同置一处，复用既有注入面零级联接线）；`ToolCallTracer` 支持「无 emitter 仅落库」装饰（emitter/recorder 任一非空即装饰），QQ 渠道、API 直调等无 SSE 链路观测盲区补齐；MCP 工具经 `ServerTaggedCallback` 标注来源 server，`McpToolProvider` 连接/发现成功与失败均结构化落库（失败隔离语义不变）；`expand_tool`/`load_skill` 元工具与 `ToolCallBudget` 预算拦截不落库；观测落库失败仅 warn 不影响主链路（与 `llm_call_log` 同口径）
  - 注：验收中的 `/api/tool-calls` 查询端点不在本次范围（另行任务）；本次验收以单测覆盖（411 通过）
  - 验收：`/api/tool-calls?sessionId=` 可查询一次编排内全部工具调用（含 MCP 工具及来源 server）；MCP 某 server 故障时能从日志定位到该 server
- [ ] **沙箱读写工具的重置与安全校验**：沙箱容器懒创建一次后无重置/重建机制（坏状态无法自愈，仅停服 `@PreDestroy` 销毁，强杀进程还会残留容器）；写入/执行工具完全信任模型输出透传容器，容器内无路径越界与危险命令拦截
  - 改造要点：容器健康探测 + 调用失败自动重建（限重建次数、超限降级空工具面并告警）；写入类工具（`WriteFileTool`/`EditFileTool`/`MoveFileTool` 等）限制在容器工作目录内，拒绝绝对路径与 `..` 越界；执行类工具（`RunShellCommandTool`/`RunPythonCodeTool`）危险命令黑名单拦截（`rm -rf /`、mkfs、fork 炸弹等）并返回可自愈提示
  - 验收：手动 kill 容器后下次工具调用自动重建成功；写入工作目录外路径被拒绝；危险命令被拦截且编排不中断

* [x] **prompt的动态加载：**
  - [x] 1.skill的动态装配
    - 实现：`SkillRepository` 扫描 `skills/` 目录 .md（front-matter 声明 name/description/agents，mtime 热重载免重启）+ `SkillManager` 两段式暴露——system prompt 只注入「名称：描述」索引段，模型按需调 `load_skill` 元工具取完整正文；越权防护按 agent 可见技能集服务端硬校验，返回全文按 tool-result-budget 截断
  - [x] 2.tool的动态装配
    - 实现：agent 表新增 `tools` 列（V10 迁移），`ToolAssignments` 优先按列声明分配（组名 `web`/`sandbox.base`… 或精确工具名，跨目录查找含 MCP 动态工具），改库即生效免重启；列 NULL/空白回退代码内置分配（legacy 语义不变）
  - [x] 3.mcp的动态装配
    - 实现：`McpToolProvider` 重构为多 server——解析 `mcp-config.json`（Claude/Cursor 同款 mcpServers 结构）全量条目（stdio/http 混合、`enabled:false` 跳过），每 server 独立懒连接 + 失败隔离，工具并集按名去重；文件缺失回退 legacy yaml 单 server
    - 附带：流式真实 usage 统计——`OpenAiChatOptions.streamUsage(true)` 让末帧回传真实 usage（prompt/completion token），llm_call_log 优先记真实值、无 usage 回退估算；`load_skill` 与 `expand_tool` 同为元工具不占工具次数额度
  - [x] 4.agent角色prompt的组装
  - [x] **5.记忆上下文的动态注入**
  - [x] 6.**工具 Schema 的延迟加载:开始只注入对于工具的描述，只有agent明确需要调用时，才会按需提升暴漏完整tool参数**

- [x] 修复目前会话无法创建goal的问题
- [x] 优化webtool工具，让他从能用到好用（借鉴 jsoup/Readability 业界管线重写抓取与网页处理，详见存档「工具与沙箱」）
- [x] 检查agent 角色提示词是否被写入了会话消息中。
- [x] 增加会话agent切换功能，当在次会话切换agent，则会话表的agentId同样需要更改。
- [x] 修复进入多agent时，请求卡住的问题。
- [x] 客户端断开后，服务端应该终止大模型请求，避免浪费token
  - 实现：`AgentChatCaller.call()` 统一流式背书（RestClient 阻塞调用不可中断，流式是唯一可中止通道），`BooleanSupplier` 取消令牌贯穿 call/stream，`takeUntil` 在 token 边界中止在途请求；详见存档「并发断连的可观测处理」

<br />

## P1 · 能力扩展主线（产品核心价值）

- [ ] **Web Search 接入**：注册搜索服务商 API（博查/Tavily/SerpAPI 等任一），新增 `search` 工具归入 `WebTools`，分配给 researcher/general——补「调研」第一步空缺，浏览器组退居 JS 渲染兜底
  - 验收：researcher 对「近期事件」类问题能返回带来源的检索结果
- [x] **RAG 知识库**（2026-09 完成）：pgvector 向量库 + DashScope text-embedding-v4 嵌入（1024 维）；knowledge/ 目录 .md/.txt 增量摄取（mtime 比对→MarkdownChunker 切分→嵌入入向量库，`POST /api/knowledge/sync`）；路径 A/B 检索增强（AgentRequestSpecFactory 唯一汇合点注入【出处N】知识段，aggregator 角色策略跳过）；出处透出（meta.sources + CLI 来源尾注）；管理端点 /api/knowledge（sync/documents/search/delete）；PG 不可用静默降级不影响启动；多知识库隔离（knowledge/ 一级子目录=kb，agent 表 knowledge 列 CSV 绑定，检索按向量 metadata.kb filterExpression 过滤防读串，kb 变更重摄取自愈）
  - 验收：knowledge/ 放本地文档 → sync 摄取 → 知识库问答，回答带【出处N】内联引用 + meta.sources + CLI 尾注 ✓
  - 待办余项：目录文件监听（WatchService）、BM25 混合检索与重排、web 管理页
- [x] **MCP 工具接入**：让 Agent 通过 MCP 连接外部工具/服务，扩展工具生态（不再逐个自研）
  - 与 Sandbox 衔接：agentscope-runtime 内置 MCP 桥接，接入时优先评估复用（见存档「Spring AI Alibaba Sandbox 接入」条目）
  - 验收：模型可调用一个外部 MCP 工具完成真实任务

## P2 · 编排智能化（中期，agent 从「能跑」到「聪明」）

- [ ] **子任务失败重规划**：子任务失败/结果为空时，把失败上下文交 lead 反思一轮（重拆 or 换专家 or 降级放行），而非直接聚合残缺结果
  - 验收：mock 某专家首次失败，端到端能看到重试/换人后的成功聚合
- [ ] **子任务依赖编排**：lead 拆解支持声明依赖（如 `depends_on`），有依赖的子任务串行、无依赖的并行，替代当前纯并行
  - 验收：「先调研 X 再基于结论写代码」类任务按依赖顺序执行
- [ ] **复杂路径 Checkpointer（可选，落库断点）**：长编排可断点续跑——如需再评估
- [ ] **Goal 失败重投（任务化，2026-09 评估后另立）**：失败 Goal 自动重投——设计要点：
  - goal 表未存 agent 路由信息，重投需 V10 迁移加 `agent_name` 路由列（重走路由会再烧一次 LLM judge，不可取）
  - 加 `retry_count` / `next_retry_at`，仅对瞬态失败（网络/超时/5xx）重投且限次数；调用层 `LlmRetry` 已有 3 次退避，Goal 级重投的增量收益在「编排中后段失败」场景
  - 防双跑复用 resume 409 机制（RUNNING 拒绝重投）；多 agent 路径优先走检查点 /resume 而非盲目重跑
  - 验收：注入瞬态失败的 Goal 在窗口期后自动重投一次成功，非瞬态失败不重投
- [ ] **长期记忆滚动摘要**：会话历史超预算被裁剪的最旧轮次，滚动压缩为一段摘要注入 system（`MemoryPolicy` 扩展），长会话不丢单
  - 验收：超长会话中早期关键信息（如用户自述偏好）仍能被引用

## P3 · 工程化与产品化（按需启动）

- [ ] **Actuator + 监控**：加 `spring-boot-starter-actuator`，暴露健康/指标端点，接 Prometheus + Grafana
  - 验收：`/actuator/health` 可用，指标可被 Prometheus 抓取
- [ ] **完整链路追踪（Micrometer Tracing）**：接 Spring AI 原生 observation（micrometer-tracing + Zipkin exporter + Zipkin 容器），补齐 `llm_call_log`（成本账本）不具备的**单次请求耗时瀑布**——HTTP → 路由 → lead 拆解 → 各专家 → 聚合每段耗时与父子 span 关系；建议与「Actuator + 监控」同批实施（共享 Micrometer 基建）
  - 验收：Zipkin UI 能看到一次聊天请求的完整瀑布图，观测埋点代码不重写（llm\_call\_log 与 Tracing 互补共存）
- [x] **链路追踪**：记录 LLM 调用耗时与 token 消耗（自建轻量方案：`llm_call_log` 表 + `LlmCallRecorder` 异步落库，`GET /api/llm-calls` 查询；Micrometer Tracing / OpenTelemetry 完整链路留待按需升级）
  - 验收：一次聊天请求的完整调用链在追踪系统可见（当前可经 `/api/llm-calls?sessionId=` 按会话查询每次调用的耗时/token/成败）
- [x] **数据库迁移工具**：Flyway 替代 `spring.sql.init` 管理 schema 演进（V1 建表 / V2 goal 索引 / V3 llm\_call\_log；存量库经 baseline 无缝接入，`spring.sql.init` 已移除）
  - 验收：新增表结构变更通过迁移脚本自动应用（新增 `db/migration/V*__*.sql` 即可，启动自动执行）
- [ ] **会话缓存**：`spring-boot-starter-data-redis` 缓存会话快照，降低 MySQL 压力
  - 验收：会话上下文命中 Redis，DB 读次数下降
- [ ] **Testcontainers 集成测试**：起真实 MySQL 环境跑集成测试
  - 验收：`mvn test` 在容器化 DB 上全绿
- [ ] **API 文档**：springdoc-openapi 自动生成 Swagger UI
  - 验收：`/swagger-ui.html` 可浏览所有接口
- [ ] **容器化交付**：Dockerfile + docker-compose（app + MySQL + Redis）一键启动
  - 验收：`docker compose up` 后完整可访问
- [ ] **CI/CD**：GitHub Actions / Gitee Go：编译 → 测试 → 构建镜像
  - 验收：push 后流水线全绿并产出镜像
- [ ] **前端 + EventSource**：Vue3/React 页面消费 SSE 实时展示，替代/补充 CLI
  - 验收：浏览器能看到打字机式流式回复
- [ ] **PDF 输出（报告导出）**：把最终回答/会话记录（Markdown）渲染为 PDF 供下载——flexmark（MD→HTML）+ openhtmltopdf（HTML→PDF，纯 Java 免外部依赖、无 AGPL 风险），中文字体内嵌资源目录（不依赖系统字体，避免容器/跨机乱码）；新增 `GET /api/export/pdf?sessionId=` 导出会话，CLI 加 `/export` 命令拉取保存
  - 验收：一次多 Agent 任务完成后可导出排版正常的中文 PDF（标题/列表/代码块/表格不乱码、不缺字）
- [ ] **消息队列**：RabbitMQ / Kafka 异步派发 Goal，长任务解耦
  - 验收：提交 Goal 后立即返回，执行与消费异步解耦
- [ ] **安全**：Spring Security + JWT 接口鉴权；API Key 走 KMS/Vault 管理
  - 验收：未带 token 的请求被拒绝
- [ ] **WebSocket**：如需全双工交互（如任务进度推送）可扩展
- [ ] **多实例水平扩展**：解除单实例假设——`failAllRunning` 启动清理、本地 `goalExecutor`、GRAPH_CHECKPOINT 单写者；分布式锁（如 ShedLock）或消息队列派发二选一，与「消息队列」条目同批评估
  - 验收：双实例同时运行，同一 Goal 不被双重执行，重启清理只影响本实例
- [ ] **Agent 行为回归评测集**：固定输入集 + 断言（路由 SIMPLE/COMPLEX 判定、专家派遣、输出约定），prompt / 模型参数改动后一条命令跑完防回归
  - 验收：评测脚本输出每项通过与耗时，可选纳入 CI
- [ ] **多模态输入（远期）**：视觉模型（qwen-vl 类）经 agent 表接入，支持图片理解类请求
  - 验收：提交图片 URL 得到基于图片内容的回答

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
- [x] **补齐 agent 表配置**：`schema.sql` 种子数据新增 `agentName='multi-agent'` 行（编排提示词），重启即生效（`INSERT IGNORE` 幂等），消除「使用默认配置」退回。
- [x] **专家 agent 派遣**：lead 拆解 JSON 升级为对象数组 `{"subtasks":[{"desc":"..","agent":"专家名"}]}`，subtask 节点按专家名查 agent 表配置取对应客户端执行；白名单校验（researcher/coder/analyst/writer/general），非法回退默认；兼容旧纯字符串格式。测试见 `MultiAgentGraphAgentTest` 派遣与回退两用例。
  - 专家清单：`multi-agent`（编排器，lead 拆解+聚合）、`researcher`（资料调研）、`coder`（代码）、`analyst`（数据分析）、`writer`（汇总撰写）、`general`（通用兜底），均登记 agent 表。
- [x] **Graph 内逐 token 流式**：聚合节点流式调用——首个 token 前推「聚合」进度行，逐 token 经旁路 sink 实时发射，主干 contentSent 短路防重复；流式失败回退阻塞调用。子任务节点保持阻塞（并行 token 会交错乱序）。`AgentChatCaller` 拆出 buildSpec 供 call/stream 共用。验证：真实 LLM 端到端 139 个 token 片段逐段到达。
- [x] **并发断连的可观测处理**：闭环「降噪 + 及时终止编排」：
  - 降噪：`ClientAbortLogFilter`（logback TurboFilter）DENY 框架 logger 的断连 ERROR；`GlobalExceptionHandler` 专 handler（AsyncRequestNotUsableException）+ 兜底 `isClientAbort` 均降级 warn 单行，业务异常不误杀
  - 取消传播：`ChatServiceImpl.doOnCancel` warn 单行（sid）→ `AgentServiceImpl.doOnCancel` goal 落库 FAILED，不残留 RUNNING
  - 编排终止：`MultiAgentGraphAgent` 主干 `doFinally(CANCEL)` 置位 cancelled → lead/子任务/聚合节点执行前短路，cancel 后零次新 LLM 调用；路径 A 响应式 cancel 天然终止
  - 在途中止（2026-09 补齐）：`AgentChatCaller.call()` 改为流式通道背书（RestClient 阻塞调用不可中断，JDK HttpClient 不响应线程中断，流式是 Spring AI 1.1.4 + JDK 连接器下唯一可中止通道）——`BooleanSupplier` 取消令牌贯穿 call/stream：置位后调用直接抛取消异常（零 HTTP 请求），执行中置位经 `takeUntil` 在下一个 token 边界中止订阅（取消向上传播关闭 HTTP 连接，厂商端停止生成）。取消不重试、部分输出不按成功返回、`llm_call_log` 记 `ok=false` 且 `error_msg` 含 `client-cancelled`；编排三节点传入共享断连标志（同步路径维持 null），聚合流式中止不回退阻塞调用。副作用：该路径 token 用量从响应 usage 真实值变为按输出文本估算（`tokensEstimated=true`）。测试：`AgentChatCallerTest`/`AgentChatCallerRetryTest` 取消用例 + `MultiAgentGraphAgentTest` 断连中止/零新增调用用例（全量 202 用例通过）
  - 测试：`ClientAbortLogFilterTest` 7 用例 + `GlobalExceptionHandlerTest` 3 用例 + cancel 落库/编排短路守护用例

## Prompt 动态装配（2026-09 完成）

- [x] **Prompt 组装管线（prompt的动态加载·子项 4）**：新增 `prompt` 包 `PromptAssembler`——每请求按 agent 名段落化组装 system prompt（角色段→工具索引段→工具纪律段→输出约定段→skill 段），两路径（`GeneralAssistantAgent`/`AgentChatCaller.buildSpec`）统一接入；`predictSubtask` 的硬编码 persona/工具纪律拼接删除、收敛为组装段。角色段优先级保持：agent 表 prompt > 角色兜底 > 默认。skill 段为扩展点接口 `SkillSectionProvider`（当前空实现）——后续子项 1「skill Markdown 目录装配」只需实现该接口注入即可，不改组装管线；子项 2/3（tool/mcp 装配数据化）可复用 `ToolAssignments.purposeOf` 元数据与工具索引段机制。测试 `PromptAssemblerTest`
- [x] **记忆上下文动态注入（prompt的动态加载·子项 5）**：`MemoryPolicy` 按角色策略注入——编排 `lead` 注入会话记忆（与路径 A general 完全同口径：`SessionService` 同源 memoryStore + `MessageChatMemoryAdvisor` + `ContextAssemblingAdvisor` 预算裁剪，无会话 ID 跳过）；聚合节点与子任务专家不注入（聚合忠实于各子任务结果，子任务上下文由 lead 在子任务描述中传递）；编排内 general 兜底专家（未指派落点）不注入。测试 `MemoryPolicyTest` + `MultiAgentGraphAgentTest` 编排注入断言
- [x] **工具 Schema 延迟加载（prompt的动态加载·子项 6）**：`ToolLazyManager` 会话级两段式暴露——首轮未展开工具仅注入轻量态（名称+用途，inputSchema 置空，直接调用返回中文引导文本不执行真实逻辑），system 经工具索引段给全量工具清单；模型调 `expand_tool(toolName)` 后工具加入会话级展开集合并返回完整参数说明，其后请求按完整 schema 注入并可执行（同请求内 expand 后自动放行，避免自愈循环）；expand\_tool 元工具不经 tracer/预算（零工具行噪声、不占执行额度）；越权展开未分配工具被拒绝。开关 `app.prompt.lazy-tools.enabled`（默认 true）关闭即回退全量注入现状。测试 `ToolLazyManagerTest`（13 用例）

## 异步治理与发布工程（2026-09-06 完成）

- [x] **后台 Goal 执行线程池治理**：`submit` 的 `CompletableFuture.runAsync`（commonPool）改为受管 `goalExecutor` 池（core=max=8、队列 50、优雅停机 30s），替代在 commonPool 上跑分钟级阻塞 LLM 调用（CPU-1 线程且 JVM 全局共用）；队列满 `RejectedExecutionException` 兜底把 Goal 落 FAILED（轮询可见，不再无声排队）；`run()` catch 从 Exception 放宽到 Throwable，Error 逃逸不再卡 RUNNING。测试：`AgentServiceImplTest` 新增 10 并发全 SUCCEEDED / 队列满兜底 / LinkageError 逃逸三用例（bdd2ac4）
- [x] **MVC 异步执行器槽位补位**：定义 goalExecutor 后 Boot 自动配置的 applicationTaskExecutor 让位，MVC 流式 SSE 派发回退每请求一线程的 `MvcSimpleAsyncTaskExecutor` 并打生产告警（反汇编定位：Boot 按 bean 名 `containsBean("applicationTaskExecutor")` 接线）；显式补 `applicationTaskExecutor` 槽位 bean（`mvc-async-` 前缀），与 goal 长任务池分离（d4a4d96）
- [x] **API Key 装载链路修复与系统级分发**：非交互 bash 不执行 .bashrc export（开头守卫 early-return），run.sh 开窗（wt.exe 新 wsl 会话）与 run-wsl.bat 显式注入路径都拿不到 key → 服务 401。key 迁仓库根 `.env.local`（gitignored，run.sh / run-wsl.bat / .bashrc 三处 source 兜底）+ 主机制升级 Windows 用户环境变量 + `WSLENV` 透传（所有 wsl 会话自动带 key）；顺带修 cmd interop 传参引号污染（1e63a8a + 系统级 setx）
- [x] **CLI 流式渲染增量直出**：渲染器每 token 整行擦除重绘（`\r\033[2K`+全缓冲）依赖终端转义支持，失效终端表现为同段文字带渐长尾巴重复（DB 取证：goal.summary 与 llm_call_log 干净、单次调用，排除模型/服务端）；改为只补打未上屏部分，纯文本行零重绘，着色行完成时才整行重绘升级。测试：`TerminalRendererTest` 重影回归用例（1ba27d6）

## 工具与沙箱（2026-08 完成）

- [x] **Agent 工具库扩充**：`tool` 包落地真实工具体系（对标 DeepSeek Harness 内置工具面），按专家分配、统一治理（宿主机自研工具后被 Sandbox 容器级隔离替代并退役）：
  - 现存自研工具：`WebTools`（fetchUrl 真实抓取，HTML→纯文本，仅 http/https，2MB/12k 字符上限）+ `DemoTools`（本地函数示例）；网页搜索待搜索服务商 API key 后补充（已列入 P1「Web Search 接入」）
  - 已退役（能力由 Sandbox 等价承接）：`ToolSandbox`/`FileTools`/`SearchTools`/`ShellTools` 连同其治理与测试一并移除
  - `ToolAssignments`：按专家分配工具集，双通道注入（@Tool 对象走 `.tools()`、ToolCallback 走 `.toolCallbacks()`）；`MultiAgentGraphAgent.call` 按子任务专家、`GeneralAssistantAgent` 按自身 agent 名；未指派子任务回退 general（全量权限的收敛见 P0「工具分配最小权限化」）
- [x] **WebTools 从能用到好用（2026-09-06）**：fetchUrl 从「正则去噪 + 纯文本」升级为「jsoup HTML5 解析 + 主内容启发式提取 + Markdown 输出」：
  - 抓取：jsoup 连接（15s 超时 / 2MB 下载上限 / 重定向 / Accept-Language），编码自动嗅探（header + meta charset，GBK 中文站不再乱码），删除「先截字节再解码」的多字节切断缺陷
  - 主内容：DOM 级去噪（噪声标签 + class/id 特征正则）+ 文本密度/链接密度评分选正文块（简化 Readability，自研纯函数；借鉴 jsoup → Readability → Markdown 业界管线，经评估未引入 Readability4J/flexmark 依赖）
  - 输出：Markdown（标题/列表/链接绝对化/代码块/管道表）+ 元数据头（标题/来源 URL）；query 意图过滤保留（按 Markdown 段落切块，未命中回退开头）
  - 缓存：拒绝式改内容式（URL→内容，50 条 / TTL 30 分钟）——重复抓取回放缓存而非「请勿重抓」，解决旧内容被上下文裁剪后无法重看的死路
  - 错误结构化：全链扫描分类（DNS/超时/连接拒绝/TLS/403/404/429/5xx/非网页 Content-Type），中文可自愈提示；单测实证 WSL DNS 隧道下域名失败表现为 ConnectException 链（判据在链底 UnresolvedAddressException，需全链扫描而非只看最深一层）
  - 测试：`WebToolsTest` 重写 16 用例（JDK 内置 HttpServer 承载本地 fixture，走真实 Jsoup.connect 链路：GBK 解码/噪声剔除/Markdown 元素/缓存回放/错误分类/截断），全量 268 用例通过；jsoup 为唯一新增依赖（MIT、零传递依赖）
- [x] **Spring AI Alibaba Sandbox 接入**：引入 `spring-ai-alibaba-sandbox`（BOM 管理版本），工具执行从进程内软隔离升级为容器级隔离。
  **架构决策**：产品定位为通用助手（调研/报告/数据分析/跑代码），agent 不操作宿主机项目文件——模型生成的所有代码/命令只在容器内执行，宿主机零暴露；不做降级路径（沙箱是硬依赖，无 Docker 环境该功能整体不可用）。
  - `SandboxToolProvider`：懒初始化（双检锁只尝试一次），失败降级空工具面（warn、不重试、不回退宿主机）；`@PreDestroy` 释放容器
  - 工具面三类：执行类（RunPythonCode/RunShellCommand）、只读文件类、写入类；浏览器组独立镜像独立初始化，失败只降级本组
  - 重合即退役：`ShellTools`/`FileTools`/`SearchTools`/`ToolSandbox` → 沙箱等价工具；`WebTools`/`DemoTools` 保留（Sandbox 未覆盖）
  - 验证：JShell 直连验证容器拉起 + Shell 执行 + 退出自动删除；浏览器组 `quotes.toscrape.com/js/`（JS 渲染页）snapshot 取得 5381 字符内容；过程记录见 `docs/reports/2026-08-28-sandbox-integration.md`
- [x] **Sandbox 真实 LLM 端到端** ✅：「Python 计算前 20 个素数和并运行验证」——lead 拆 1 个子任务指派 coder，`RunPythonCodeTool` 经容器 fastapi 执行，SSE 进度完整、`meta SUCCEEDED`、结果正确（素数和 639）；容器随首次工具调用懒创建、优雅停服随 `@PreDestroy` 销毁（注意：强杀 `mvn spring-boot:run` 不触发优雅关闭会残留容器）

## CLI 体验（2026-08 完成）

- [x] **CLI 输出优化（对标 Claude Code 的终端体验）**：
  - 流式 Markdown 渲染：逐 token 渲染标题/列表/**粗体**/代码块语法高亮
  - 过程状态原位刷新：进度 spinner + 已耗时固定状态行刷新，完成后折叠归档灰色 ✓ 摘要行，不刷屏
  - 工具调用行：服务端 `ToolCallTracer` 装饰 ToolCallback（schema 原样透传），起止经旁路 sink 发 `tool`/`tool-done` 进度行（路径 A/B 通用）；CLI 展示 `⏺ 工具名(参数摘要)` → `✓ 耗时 · +N/-M 行`（diff 近似着色）
  - 回合小结：耗时 / 子任务数 / token 近似量
  - 输入体验：JLine 3——历史持久化、`/` 命令 Tab 补全、多行粘贴；无 TTY 自动降级。**JLine 3 不可替代性**：Windows 控制台无纯 Java 逐键 raw 输入；启动方式 `mvn -s .mvn/settings.xml -Pcli compile exec:exec`（fork 独立进程接管真实终端）
  - 乱码修复：CLI 输出统一收敛到 JLine `terminal.writer()` 宽字符通道（WriterBridge），GBK/65001 代码页均正常
  - 验收 ✅：全量 101 用例通过；文档 `docs/reports/2026-08-28-cli-output-optimization.md`
- [x] **回答归属前缀（2026-09-06）**：SSE 流首新增 `stage=agent` 进度行——服务端下发实际路由到的 agent 名（智能分流与指定 agentId 两条路径都覆盖，续跑固定 multi-agent），CLI 在首个回答 token 前渲染着色「agentName> 」前缀，与用户侧「你> 」提示符对称，用户/智能体一眼可分；前缀计入渲染器行状态，首行着色重绘时原样补回（不被 CLEAR_LINE 擦掉）

## 项目基础（Roadmap P0 及能力项）

- [x] **Goal 落库**：Goal 从内存 `ConcurrentMap` 迁移到 MySQL，重启不丢；提供目标历史查询接口
- [x] **清理遗留依赖**：移除未使用的 JLine、Hutool 及无用 dependencyManagement 条目
- [x] **清理遗留 SQL**：`schema.sql` 重写废弃 `goal` 表
- [x] **统一参数校验**：`spring-boot-starter-validation` 注解校验，非法请求返回统一 400 错误体
- [x] **全局异常处理**：`@RestControllerAdvice` 统一 `{code, message}` 响应
- [x] **单 agent 系统**：创建 agent 表并引入项目
- [x] **多模型接入**：`ChatAgentConfig` 按 agent 表装配多个 Agent（qwen-plus/max/turbo），新增 Agent = agent 表一行（2026-09 起表驱动自动注册，见下条）
- [x] **多 Agent 编排（初版）**：主 Agent 判定 COMPLEX 路由到 `multi-agent` 完成 lead→并行→聚合闭环
- [x] **响应式流式改造**：流式端点已直接返回 `Flux<String>`（text/event-stream），DB 阻塞操作经 `Schedulers.boundedElastic()` 边界隔离；同步 `/api/chat` 保留
- [x] **会话上下文管理与 Token 裁剪**：`ContextAssemblingAdvisor` 按 token 预算裁剪，长对话体积受控
- [x] **Mockito 单测（核心场景）**：10 个测试类 / 50 用例覆盖路由判定、双路径执行、进度协议、SSE 契约（详见 docs/functional-testing.md）
- [x] **Agent 表驱动自动注册（2026-09）**：新增 `AgentRegistry`——启动时读 agent 表把全部 `is_internal=0` 行自动注册为对话 Agent（`GeneralAssistantAgent` 按行配置生效），路由未命中惰性查表热注册（`ConcurrentHashMap.computeIfAbsent` 原子构造，运行中插行免重启）；V9 迁移加 `is_internal` 列（multi-agent/lead/aggregator 置 1，排除逻辑纯数据驱动，新增内部角色免改代码）；启动逐行 fail-safe（脏行 warn 跳过）；`general` 行缺失时代码兜底注册并 warn（DB 行存在则完全按 DB，两来源不合并）。`ChatAgentConfig` 删除 generalAgent/deepseekAgent 手工 bean，`AgentServiceImpl` 路由委托 Registry（构造 `@Lazy` 解创建期循环）。注册口径收敛为：**新增 Agent = agent 表一行（is\_internal=0）**。测试：`AgentRegistryTest` 9 用例 + `AgentServiceImplTest` 适配扩展（全量 249 用例通过）

***

## 备注

- 测试全景见 [docs/functional-testing.md](./functional-testing.md)，数据流详解见 [docs/data-flow.md](./data-flow.md)，技术栈对照见 [TECH\_STACK.md](./TECH_STACK.md)。
- 每项完成后按对应"验收"标准验证后再勾选。

