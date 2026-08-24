# javaHarness

基于 **Spring AI** 的 AI Agent 编排框架 Demo。封装了 Agent 编排 + 目标（Goal）生命周期管理，并提供 REST 接口与命令行聊天客户端。

## 技术栈

| 层面 | 技术 | 说明 |
|---|---|---|
| 框架 | Spring Boot 3.5.14 | 应用骨架、依赖注入、REST、自动配置 |
| AI 模型接入 | Spring AI 1.1.4 + `spring-ai-starter-model-openai` | 通过 OpenAI 兼容协议接入多个服务商（DashScope / DeepSeek），`Registry` 模式按 model 路由 |
| 大模型 | 多模型（qwen-plus/max/turbo、gpt-4o、deepseek-chat 等） | 由 `model_provider` 表 + `agent` 表共同决定 |
| 命令行交互 | `ChatCli`（自研循环） | 交互式终端：直接输入文本对话，`/agent` 切换 Agent |
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
│   └── HarnessController.java    # 管理接口（agents / submit / goals）
├── service/                      # 业务层（接口）
│   ├── AgentService.java         # Agent 编排：路由、执行目标、回写状态
│   ├── GoalService.java          # 目标生命周期管理
│   ├── SessionService.java       # 多轮会话记忆（session + session_messages）
│   ├── ChatService.java          # 聊天用例编排（同步 / 流式 / SSE）
│   ├── AgentConfigProvider.java  # 从 agent 表读取运行配置（路由映射）
│   └── impl/                     # 业务实现（AgentServiceImpl 等）
├── config/                       # 配置与装配
│   ├── ChatAgentConfig.java      # 注册多个 GeneralAssistantAgent（general/writer/coder/deepseek）
│   ├── ChatClientFactory.java    # 按服务商构建 OpenAI 兼容 ChatClient（Registry 模式）
│   └── ChatClientRegistry.java   # 模型名 → ChatClient 注册表（从 model_provider 表加载）
├── mapper/                       # 数据访问层：MyBatis-Plus Mapper
│   ├── AgentMapper / GoalMapper / SessionMapper / SessionMessageMapper / ModelProviderMapper
├── domain/                       # 领域模型（父包）
│   ├── Goal.java                 # 目标 + 状态（PENDING/RUNNING/SUCCEEDED/FAILED）
│   ├── AgentConfig.java          # Agent 运行配置（model + prompt），来自 agent 表
│   ├── dto/                      # 传输对象（请求/响应体）
│   │   ├── ChatRequest / ChatResponse / ErrorResponse / SseMeta
│   │   └── AgentsView / GoalView / GoalsView / SubmitView
│   └── entity/                   # 数据库实体（对应 agent / goal / session / model_provider 表）
│       ├── AgentEntity / GoalEntity / SessionEntity / SessionMessageEntity / ModelProviderEntity
├── enums/                        # 枚举：ExecutionType、GoalStatus
├── exception/                    # 全局异常处理（@RestControllerAdvice）
├── agent/                        # Agent 抽象与实现
│   ├── Agent.java                # Agent 接口：name() / execute() / executeStream()
│   └── GeneralAssistantAgent.java # 通用 Agent：按 model 从 Registry 取客户端调用大模型
├── cli/
│   ├── ChatCli.java              # 命令行聊天客户端（独立进程，纯 HTTP 连 8080）
│   └── api/ChatApiClient.java    # OkHttp 封装 /api/chat 与 /api/chat/stream(SSE)
└── tool/
    └── DemoTools.java            # 示例工具集（时间 / 计算 / 天气）
```

> 分层职责：
> - **controller** 收发表单/REST/SSE，不承载业务逻辑
> - **service** 编排核心逻辑，接口与实现分离（`service` 接口 + `service.impl`）
> - **mapper / domain.entity** 负责数据库读写与映射；`domain` 承载纯领域模型（Goal、AgentConfig）

## 环境要求

- JDK 17+
- Maven 3.8+（项目使用项目内仓库，无需全局安装额外配置）
- MySQL（`harness` 库，见 `application.yaml` 与 `sql/schema.sql`）
- （可选）API Key：DashScope（通义千问）/ DeepSeek。不配置也能启动，但调用模型会返回 `invalid_api_key`。

## 多模型与多服务商（Registry 模式）

项目支持**多 Agent + 多模型服务商**，接入手性完全由数据库驱动：

- **Agent**（`agent` 表）：每行定义一个 Agent（`agent_name`/`model`/`prompt`），对应一个已在 [ChatAgentConfig](src/main/java/com/dark/javaHarness/config/ChatAgentConfig.java) 注册的 bean 实例（general / writer / coder / deepseek）。
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
- `/agent <id>` 切换到指定 Agent（agent 表主键）、`/agent` 查看当前、`/help` `/exit` 帮助/退出

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
data: 片段1
data: 片段2
...
data: [DONE]
event: meta
data: {"sessionId":"9","newSession":true,"goalId":"goal-1","status":"SUCCEEDED"}
```

- 每段 `data:` 为模型生成的一个文本片段（逐 token 推送）
- 全部推完发送 `[DONE]`
- 末尾 `event: meta` 携带 `sessionId` / `newSession` / `goalId` / `status`；失败时 `status=FAILED` 且追加 `error` 字段
- 可用 `curl -N` 观察逐段到达

## 核心流程

```
CLI / REST 请求（可携带 agentId）
   → AgentService（编排层）路由到对应 Agent（agentId → agentName，未命中回退 general）
     → 创建 Goal: PENDING → RUNNING
       → GeneralAssistantAgent 读取 agent 表配置（model + prompt），
         按 model 从 ChatClientRegistry 取对应服务商客户端调用大模型
         （Spring AI ChatClient，同步 / 逐 token 流式）
       → 回写 Goal: SUCCEEDED / FAILED（含 summary）
```

聊天请求会作为 Goal 留存，可通过 `/api/harness/goals` 查询历史记录。

## 运行测试

项目包含单元测试（基于 JUnit 5 + Mockito，不依赖真实数据库 / 网络 / API Key）：

```bash
mvn -s .mvn/settings.xml test
```

覆盖范围：

| 测试 | 验证点 |
|---|---|
| `AgentServiceImplTest` | 多 Agent 路由：`agentId` → `writer` / 未命中回退 `general` / `null` 走默认 |
| `AgentConfigProviderTest` | 从 `agent` 表读取配置（model / prompt）：命中、缺失、空白降级 |
| `ChatClientRegistryTest` | 多服务商：新增 `gpt-4o` 命中、禁用模型回退默认客户端 |
| `ChatServiceImplTest` | 多轮记忆：首轮 `newSession=true` 建档、带 sessionId 复用、成功写回上下文 |
| `ChatServiceImplStreamTest` | 流式：逐 token 回调、收集完整回复、FAILED 返回 error |

> 注意：`JavaHarnessApplicationTests` 是 `@SpringBootTest`，会尝试连接本机 MySQL；在无数据库环境运行该单个类可能因连接失败而报错（其余业务测试不受影响）。