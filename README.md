# javaHarness

基于 **Spring AI** 的 AI Agent 编排框架 Demo。封装了 Agent 编排 + 目标（Goal）生命周期管理，并提供 REST 接口与命令行聊天客户端。

## 技术栈

| 层面 | 技术 | 说明 |
|---|---|---|
| 框架 | Spring Boot 3.5.14 | 应用骨架、依赖注入、REST、自动配置 |
| AI 模型接入 | Spring AI 1.1.4（`spring-ai-starter-model-openai`） | 通过 OpenAI 兼容协议接入通义千问（DashScope），零改动即可切换任意 OpenAI 兼容的模型服务（DeepSeek 等） |
| 大模型 | 通义千问 `qwen3.7-plus` | DashScope `compatible-mode` 端点 |
| 命令行交互 | `ChatCli`（自研循环） | 交互式终端：直接输入文本对话，`/agent` 切换 Agent |
| HTTP | OkHttp 4.12.0 | CLI 端调用主服务 REST/SSE（`cli/api/ChatApiClient`） |
| ORM | MyBatis-Plus 3.5.7 | `goal` / `session` / `session_messages` / `agent` 表的 CRUD |
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
│   └── impl/                     # 业务实现
├── mapper/                       # 数据访问层：MyBatis-Plus Mapper
│   ├── AgentMapper / GoalMapper / SessionMapper / SessionMessageMapper
├── domain/                       # 领域模型（父包）
│   ├── Goal.java                 # 目标 + 状态（PENDING/RUNNING/SUCCEEDED/FAILED）
│   ├── AgentConfig.java          # Agent 运行配置（model + prompt），来自 agent 表
│   ├── dto/                      # 传输对象（请求/响应体）
│   │   ├── ChatRequest / ChatResponse / ErrorResponse
│   │   └── AgentsView / GoalView / GoalsView / SubmitView
│   └── entity/                   # 数据库实体（对应 agent / goal / session 表）
│       ├── AgentEntity / GoalEntity / Session / SessionMessage
├── enums/                        # 枚举：ExecutionType、GoalStatus
├── exception/                    # 全局异常处理（@RestControllerAdvice）
├── agent/                        # Agent 抽象与实现
│   ├── Agent.java                # Agent 接口：name() / execute() / executeStream()
│   └── GeneralAssistantAgent.java # “general”Agent：Spring AI ChatClient 调用千问
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
- （可选）通义千问 API Key：阿里云百炼平台 <https://bailian.console.aliyun.com/> 获取

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

## 核心流程

```
CLI / REST 请求（可携带 agentId）
   → AgentService（编排层）路由到对应 Agent（agentId → agentName，未命中回退 general）
     → 创建 Goal: PENDING → RUNNING
       → GeneralAssistantAgent 读取 agent 表配置（model + prompt）调用千问
         （Spring AI ChatClient，同步 / 逐 token 流式）
       → 回写 Goal: SUCCEEDED / FAILED（含 summary）
```

聊天请求会作为 Goal 留存，可通过 `/api/harness/goals` 查询历史记录。