# javaHarness

基于 **Spring AI** 的 AI Agent 编排框架 Demo。封装了 Agent 编排 + 目标（Goal）生命周期管理，并提供 REST 接口与命令行聊天客户端。

## 技术栈

| 层面 | 技术 | 说明 |
|---|---|---|
| 框架 | Spring Boot 3.5.14 | 应用骨架、依赖注入、REST、自动配置 |
| AI 模型接入 | Spring AI 1.1.4 + `spring-ai-starter-model-openai` | 通过 OpenAI 兼容协议接入多个服务商（DashScope / DeepSeek），`Registry` 模式按 model 路由 |
| Graph 编排 | `spring-ai-alibaba-graph-core`（BOM 1.1.2.2） | 复杂任务多 Agent 编排：StateGraph「lead 拆解 → 并行子任务 → 聚合」+ 生命周期钩子进度推送 |
| 沙箱工具 | `spring-ai-alibaba-sandbox`（BOM 1.1.2.2） | 容器级工具执行隔离（agentscope-runtime）：Python/Shell/文件 + 浏览器导航快照，宿主机零暴露（需本机 Docker） |
| 大模型 | 多模型（qwen-plus/max/turbo、gpt-4o、deepseek-chat 等） | 由 `model_provider` 表 + `agent` 表共同决定 |
| 命令行交互 | `ChatCli`（自研循环） | 交互式终端：直接输入文本对话；`/agent <id>` 切换专家、`/agent off` 关闭手动指定恢复智能分流 |
| HTTP | OkHttp 4.12.0 | CLI 端调用主服务 REST/SSE（`cli/api/ChatApiClient`） |
| ORM | MyBatis-Plus 3.5.7 | `goal` / `session` / `session_messages` / `agent` / `model_provider` 表的 CRUD |
| 参数校验 | Jakarta Validation | `spring-boot-starter-validation` + `@Valid` |
| JSON | Jackson | DTO 序列化 / SSE meta 解析（随 starter 引入） |
| 构建 | Maven | 项目管理与打包，项目内本地仓库 `.mvn-repo` |
| 语言 | Java 17 | — |

## 项目结构（分层架构）

采用经典分层（Controller → Service → Mapper/Entity），领域模型统一收纳在 `domain` 父包下：

```
src/main/java/com/dark/javaHarness/
├── JavaHarnessApplication.java   # Spring Boot 启动类（@MapperScan 指向 com.dark.javaHarness.mapper）
├── controller/                   # 表现层：REST 接口 + SSE 流式
│   ├── ChatController.java       # 聊天接口（/api/chat、/api/chat/stream）
│   ├── HarnessController.java    # 管理接口（agents / submit / goals）
│   └── LlmCallController.java    # LLM 调用观测查询（/api/llm-calls）
├── service/                      # 业务层（接口）
│   ├── AgentService.java         # Agent 编排：路由、执行目标、回写状态
│   ├── GoalService.java          # 目标生命周期管理
│   ├── SessionService.java       # 多轮会话记忆（session + session_messages）
│   ├── ChatService.java          # 聊天用例编排（同步 / 流式 / SSE / 进度事件转换）
│   ├── RouteJudge.java           # 主 Agent 路由判断（SIMPLE / COMPLEX 分流）
│   ├── AgentConfigProvider.java  # 从 agent 表读取运行配置（路由映射）
│   └── impl/                     # 业务实现（AgentServiceImpl / ChatServiceImpl / LlmRouteJudge / LlmCallRecorder 观测落库 等）
├── advisor/                      # Spring AI Advisor 拦截器（Agent 流程横切管理）
│   └── ContextAssemblingAdvisor.java  # 上下文组装：过滤/截断/role 归一化（token 预算）
├── config/                       # 配置与装配
│   └── agent/                    # Agent 配置注册（Agent bean + 多服务商客户端注册）
│       ├── ChatAgentConfig.java      # 注册 GeneralAssistantAgent（general / deepseek）
│       ├── ChatClientFactory.java    # 按服务商构建 OpenAI 兼容 ChatClient（Registry 模式）
│       └── ChatClientRegistry.java   # 模型名 → ChatClient 注册表（从 model_provider 表加载）
├── mapper/                       # 数据访问层：MyBatis-Plus Mapper
│   ├── AgentMapper / GoalMapper / SessionMapper / SessionMessageMapper / ModelProviderMapper / LlmCallLogMapper
├── domain/                       # 领域模型（父包）
│   ├── Goal.java                 # 目标 + 状态（PENDING/RUNNING/SUCCEEDED/FAILED）
│   ├── AgentConfig.java          # Agent 运行配置（model + prompt），来自 agent 表
│   ├── RouteDecision.java        # 路由决策枚举（SIMPLE / COMPLEX）
│   ├── LlmCallLog.java           # 一次 LLM 调用的观测记录（耗时/token/成败，LlmCallRecorder 落库）
│   ├── dto/                      # 传输对象（请求/响应体）
│   │   ├── ChatRequest / ChatResponse / ErrorResponse / SseMeta
│   │   ├── AgentsView / GoalView / GoalsView / SubmitView
│   │   └── PageResult / SessionPageView（分页）
│   └── entity/                   # 数据库实体（对应 agent / goal / session / model_provider / llm_call_log 表）
│       ├── AgentEntity / GoalEntity / SessionEntity / SessionMessageEntity / ModelProviderEntity / LlmCallLogEntity
├── enums/                        # 枚举与共享常量：GoalStatus、AgentConstants
├── exception/                    # 全局异常处理（@RestControllerAdvice）
├── agent/                        # Agent 抽象与实现
│   ├── Agent.java                # Agent 接口：name() / execute() / executeStreamReactive()
│   ├── GeneralAssistantAgent.java  # 路径 A：单次调用大模型（同步 call() / 真·逐 token stream()）
│   ├── MultiAgentGraphAgent.java   # 路径 B：StateGraph 多 Agent 编排（lead→并行子任务→聚合）+ 钩子进度旁路
│   ├── AgentChatCaller.java        # LLM 单次调用封装（查 agent 表配置 → 取客户端 → 组装 → 工具注入）
│   ├── BranchProgressListener.java # graph-core 生命周期钩子旁路（并行分支完成事件串行发射）
│   └── ProgressLine.java           # 进度行线协议（MARK+stage+SEP+detail）编解码，服务端↔CLI 共用
├── cli/
│   ├── ChatCli.java              # 命令行聊天客户端（独立进程，纯 HTTP 连 8080）
│   └── api/ChatApiClient.java    # OkHttp 封装 /api/chat 与 /api/chat/stream(SSE)
└── tool/
    ├── WebTools.java             # 网页抓取工具（fetchUrl：HTML→纯文本，仅 http/https，限长）
    ├── DemoTools.java            # 示例工具集（时间 / 计算 / 天气）
    ├── SandboxToolProvider.java  # 容器级沙箱工具（agentscope：Python/Shell/文件 + 浏览器，懒初始化、失败降级空工具面）
    └── ToolAssignments.java      # 工具分配表：按专家分配工具集（@Tool 对象 + ToolCallback 双通道，最小权限）
```

> 分层职责：
> - **controller** 收发表单/REST/SSE，不承载业务逻辑
> - **service** 编排核心逻辑，接口与实现分离（`service` 接口 + `service.impl`）
> - **mapper / domain.entity** 负责数据库读写与映射；`domain` 承载纯领域模型（Goal、AgentConfig）

## 环境要求

- JDK 17+
- Maven 3.8+（项目使用项目内仓库，无需全局安装额外配置）
- MySQL（`harness` 库，连接配置见 `application.yaml`；schema 由 Flyway 管理，启动自动执行 `src/main/resources/db/migration/` 迁移脚本，无需手动建表）
- Docker Desktop（**沙箱工具硬依赖**：模型生成的 Python/命令与浏览器操作在容器内执行，宿主机零暴露；两个镜像需预拉取，见 `TECH_STACK.md` 注意事项。无 Docker 时沙箱类工具整体不可用，应用其余功能不受影响）
- （可选）API Key：DashScope（通义千问）/ DeepSeek。不配置也能启动，但调用模型会返回 `invalid_api_key`。

## 多模型与多服务商（Registry 模式）

项目支持**多 Agent + 多模型服务商**，接入手性完全由数据库驱动：

- **Agent**（`agent` 表）：每行定义一个 Agent（`agent_name`/`model`/`prompt`），对应一个已在 [ChatAgentConfig](src/main/java/com/dark/javaHarness/config/ChatAgentConfig.java) 注册的 bean 实例。种子行：`general`/`deepseek`（聊天）、`multi-agent`（编排器）、`lead`（子任务拆解器）、`aggregator`（结果聚合器）、`researcher`/`coder`/`analyst`/`writer`（专家，供 lead 按子任务指派）。
- **模型映射**（`model_provider` 表）：每行定义 `model → (provider, api_url, status)`。
  - 新增模型/服务商 = 表里加一行（`status=1` 启用），重启即加载；无需改代码。
  - `status=0` 禁用 → 该模型回退到默认 DashScope 客户端。
- **路由**：请求可携带 `agentId`（agent 表主键），服务端映射为 `agentName` 后路由；未命中或为空时回退默认 `general`。
- **链路**：`agent.model` 引用 `model_provider.model`，由 `ChatClientRegistry` 运行时按 model 取对应厂商的 ChatClient。

API Key 出于安全不落库，按服务商标识从环境读取：
- `dashscope` → `spring.ai.openai.api-key`（环境变量 `DASHSCOPE_API_KEY` / `QWEN_API`）
- `deepseek` → `app.deepseek.api-key`（环境变量 `DEEPSEEK_API_KEY`）

## 启动方式（双进程模型）

> 说明：项目使用项目内本地仓库，需通过 `-s .mvn/settings.xml` 指定自定义 settings（本地仓库落在项目内 `.mvn-repo`），避免写入系统 Maven 仓库目录。
>
> 架构 = **主进程（服务/8080）** + **CLI（独立进程，纯客户端不占端口）**。

### ⚡ 一键启动（推荐）

Windows 下直接**双击项目根目录的 `run.bat`**，脚本会自动：

1. 编译项目
2. 打开「主服务」窗口（监听 8080）
3. 等主服务就绪后自动打开「CLI 聊天」窗口

两个窗口同时弹出，无需手动敲 Maven 命令。脚本内部已带 `-s .mvn/settings.xml`。

### 手动启动步骤

```powershell
mvn -s .mvn/settings.xml spring-boot:run
```

- 监听 **8080**，提供 REST 接口
- 日志正常输出到控制台

### 2. 启动 CLI 聊天（另开终端）

```bash
mvn -s .mvn/settings.xml exec:java
```

CLI 是纯 HTTP 客户端，**不监听任何端口**（占用的是你当前的终端进程），通过 REST 调用主进程的 `/api/chat` 完成对话：

```
你> 你是谁
千问> 我是通义千问，一个AI助手...
```
- 直接输入文本 → 与当前 Agent（默认 general）聊天
- 多轮记忆：自动创建会话，后续请求携带 sessionId 延续上下文
- `/new [名称]` 新建会话并切换（旧会话保留）；`/agent <id>` 切换到指定 Agent（agent 表主键）、`/agent` 查看当前、`/help` `/exit` 帮助/退出

### 3. REST 直接聊天（无需 CLI）

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"你好"}'
```

### 4.（可选）配置真实千问 API Key

不配置也能启动，但调用模型会返回 401（`invalid_api_key`）。配置后即可真实对话：

```bash
# Windows PowerShell
$env:QWEN_API = "sk-你的key"
```

重启服务后，聊天即可收到千问回复。

## REST 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/harness/agents` | 已注册的 Agent 列表 |
| GET | `/api/harness/goals` | 目标（含聊天记录）与状态 |
| GET | `/api/harness/goals/{id}` | 查询单个目标状态 |
| POST | `/api/harness/submit?agent=general&objective=...` | 提交一个异步目标 |
| POST | `/api/harness/sessions` | 新建会话（可选 `name`，默认「新会话」），返回 sessionId/name |
| GET | `/api/llm-calls?sessionId=&limit=` | LLM 调用观测：每次调用的耗时/token/成败（按会话过滤，默认 50 条） |
| POST | `/api/chat` | 同步聊天：`{"message":"你好","agentId":1}` |
| POST | `/api/chat/stream` | 流式聊天（SSE）：同请求体，逐 token 推送 |

> `agentId` 可选（对应 agent 表主键）：为空走默认 Agent（general）。

示例：

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"你好"}'
```

### SSE 流式协议（`POST /api/chat/stream`）

响应 `Content-Type: text/event-stream`，事件流如下：

```
event: progress
data: {"stage":"编排","detail":"开始拆解复杂目标…"}
event: progress
data: {"stage":"拆解","detail":"4 个子任务已就绪"}
data: 片段1
data: 片段2
...
data: [DONE]
event: meta
data: {"sessionId":"9","newSession":true,"goalId":null,"status":"SUCCEEDED","error":null}
```

- **内容 token**：普通 `data:` 行为模型生成的文本片段（逐 token 推送；行内换行已转义保证单行完整）
- **执行进度**：`event: progress` + `data: {stage, detail}` JSON——复杂任务（多 Agent 编排）各阶段实时推送（编排/拆解/子任务完成/聚合），进度不计入会话记忆
- 全部推完发送 `[DONE]`
- 末尾 `event: meta` 携带 `sessionId` / `newSession` / `goalId` / `status`；失败时 `status=FAILED` 且追加 `error` 字段
- 每个 SSE 事件以单个元素成对输出（`event:`+`data:` 不会被其它事件交叉打断）
- 可用 `curl -N` 观察逐段到达

## 核心流程

Harness 作为请求的执行外壳，统一从应用层接入，经上下文组装后交由主 Agent 前置判断分流——**不是所有请求都强制走复杂多 Agent 流程**，复杂任务仅在需要时才进入多 Agent 编排：

```
1. 请求进入 Harness
   CLI / REST 请求（message / sessionId / agentId）→ ChatController（Harness 外壳入口）

2. 加载会话原始数据 + 执行上下文组装
   sessionId → 读 session_messages → SessionService.loadContext() 还原 Message 列表
   → 过滤 / 截断 / 角色格式化 → 组装执行上下文

3. 主 Agent 前置判断（RouteJudge，LLM 决策 SIMPLE / COMPLEX，失败兜底 SIMPLE）
   ├─ 场景 A：问题简单（无需工具、无需拆分子任务）
   │     → 普通单次调用芯片：GeneralAssistantAgent 读 agent 表配置取 ChatClient
   │       直接单次调用大模型（同步 call() / 响应式 stream() 真·逐 token 推送，含会话记忆）
   └─ 场景 B：问题复杂（搜索 / 代码 / 多步骤处理）
         → 多 Agent 编排链路（spring-ai-alibaba-graph-core StateGraph）：
           Lead Agent 按难度拆解（至多 4 条、禁凑数）→ 子 Agent 并行执行 → 聚合
           子任务按专家查 agent 表配置取客户端，并按 ToolAssignments 注入工具
           （沙箱执行/文件/浏览器 + 网页抓取，未分配的工具对模型不可见）
           生命周期钩子旁路实时推送各阶段进度（event: progress SSE）

4. 统一出口
   两条路径都写回 Goal（SUCCEEDED / FAILED）+ 会话记忆（进度行不计入），经同步 / SSE 统一响应
```

> 数据流细节见 [`docs/data-flow.md`](./docs/data-flow.md)，落地 TODO 见 [`HARNESS_TODO.md`](./HARNESS_TODO.md)，测试全景见 [`docs/functional-testing.md`](./docs/functional-testing.md)。

## 运行测试

项目包含单元测试（基于 JUnit 5 + Mockito，不依赖真实数据库 / 网络 / API Key；当前 11 个测试类 / 75 用例全绿）：

```bash
mvn -s .mvn/settings.xml test
```

| 测试 | 验证点 |
|---|---|
| `LlmRouteJudgeTest` | 主 Agent 分流判定：简单/复杂/异常 JSON 兜底 |
| `AgentServiceImplTest` | 多 Agent 路由：`agentId` → `writer` / 未命中回退 `general` / `null` 走默认 |
| `AgentConfigProviderTest` | 从 `agent` 表读取配置（model / prompt）：命中、缺失、空白降级 |
| `ChatClientRegistryTest` | 多服务商：新增 `gpt-4o` 命中、禁用模型回退默认客户端 |
| `ChatControllerTest` | Controller 层：流式 Flux 逐元素 + 换行、同步接口契约 |
| `ContextAssemblingAdvisorTest` | 上下文组装：token 预算裁剪、role 归一化边界 |
| `ChatServiceImplTest` | 多轮记忆、SSE 契约（progress/meta/error 单元素成对）、内容换行转义 |
| `GeneralAssistantAgentTest` | 路径 A：逐 token 渐进发射（防伪流式回归）、同步 execute |
| `MultiAgentGraphAgentTest` | 路径 B：StateGraph 编排闭环、进度阶段时序（防死锁/丢事件回归）、专家派遣与白名单回退、流式进度事件次序 |
| `ProgressLineTest` | 进度线协议编解码 |
| `ToolAssignmentsTest` | 工具分配：双通道注入、专家分配语义、未登记专家空集不触发沙箱初始化 |
| `WebToolsTest` | 网页抓取：HTML→纯文本、协议白名单（http/https） |

> 注意：`JavaHarnessApplicationTests` 是 `@SpringBootTest`，会尝试连接本机 MySQL；在无数据库环境运行该单个类可能因连接失败而报错（其余业务测试不受影响）。

## 参考与致谢

本项目的设计在以下优秀开源项目/产品的启发下完成，特此致谢：

| 参考 | 对应借鉴 |
|---|---|
| [Deer-Flow](https://github.com/bytedance/deer-flow)（字节跳动） | 多 Agent 编排范式：「Coordinator → Planner 拆解 → 专家并行执行 → Reporter 汇总」的整体架构，以及 researcher / coder / analyst / writer 专家角色划分，直接启发了本项目的 StateGraph「lead 拆解 → 并行子任务 → 聚合」编排与专家 Agent 体系 |
| [Claude Code](https://github.com/anthropics/claude-code)（Anthropic） | CLI 终端体验：spinner 进度原位刷新 + 完成折叠归档、工具调用行（`⏺ 工具名(参数)` → `✓ 耗时`）、diff `+绿/-红` 着色、回合小结等交互设计，均对标 Claude Code 的渲染风格实现（`TerminalRenderer`） |
| [DeepSeek](https://github.com/deepseek-ai)（deepseek-ai） | Agent 工具库设计：网页抓取（fetchUrl）、文件/命令类工具的能力面划分，以及按 Agent 分配工具的最小权限思路，参考了 DeepSeek 的工具设计实践 |