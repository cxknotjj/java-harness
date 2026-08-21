# javaHarness

基于 **Spring AI** 的 AI Agent 编排框架 Demo。封装了 Agent 编排 + 目标（Goal）生命周期管理，并提供 REST 接口与命令行聊天客户端。

## 技术栈

| 层面 | 技术 | 说明 |
|---|---|---|
| 框架 | Spring Boot 3.5.14 | 应用骨架、依赖注入、REST、自动配置 |
| AI 模型接入 | Spring AI 1.1.4（`spring-ai-starter-model-openai`） | 通过 OpenAI 兼容协议接入通义千问（DashScope），零改动即可切换任意 OpenAI 兼容的模型服务（DeepSeek 等） |
| 大模型 | 通义千问 `qwen3.7-plus` | DashScope `compatible-mode` 端点 |
| 命令行交互 | JLine 3 | 交互式终端：命令历史、行编辑、Tab 补全 |
| JSON | Jackson | 由 spring-boot-starter-web 自带，CLI 的 JSON 解析/序列化 |
| HTTP | JDK HttpClient | JDK 17 内置；服务端与 CLI 间通信 |
| 构建 | Maven | 项目管理与打包 |
| 语言 | Java 17 | — |

## 项目结构

```
src/main/java/com/dark/javaHarness/
├── JavaHarnessApplication.java   # Spring Boot 启动类
├── agent/
│   └── GeneralAssistantAgent.java # “general”Agent：基于 Spring AI ChatClient 调用千问
├── core/
│   ├── agent/
│   │   ├── Agent.java            # Agent 接口：name() + execute(Goal)
│   │   └── AgentService.java     # 编排服务：路由 Agent、执行目标、回写 Goal 状态
│   └── goal/
│       ├── Goal.java             # 目标实体 + 状态（PENDING/RUNNING/SUCCEEDED/FAILED）
│       └── GoalManager.java      # 目标生命周期管理
├── web/
│   ├── HarnessController.java    # REST：agents / goals / submit
│   └── ChatController.java       # REST：聊天接口（走编排层）
└── cli/
    └── ChatCli.java               # 命令行聊天客户端（独立进程，纯HTTP连8080）
```

## 环境要求

- JDK 17+
- Maven 3.8+（项目使用项目内仓库，无需全局安装额外配置）
- （可选）通义千问 API Key：阿里云百炼平台 <https://bailian.console.aliyun.com/> 获取

## 启动方式（双进程模型）

> 说明：项目使用项目内本地仓库，需通过 `-s .mvn/settings.xml` 指定自定义 settings（本地仓库落在项目内 `.mvn-repo`），避免写入系统 Maven 仓库目录。
>
> 架构 = **主进程（服务/8080）** + **CLI（独立进程，纯客户端不占端口）**。

### 1. 启动主服务（监听 8080，保留日志）

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
- 直接输入文本 → 与 general agent（千问）聊天
- `/help`、`/exit` 查看帮助/退出

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
| POST | `/api/harness/submit?agent=general&objective=...` | 提交一个异步目标 |
| POST | `/api/chat` | 聊天：`{"message":"你好"}` → `{"goalId","status","reply"}` |

示例：

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"你好"}'
```

## 核心流程

```
CLI / REST 请求
   → AgentService（编排层）路由到 general Agent
     → 创建 Goal: PENDING → RUNNING
       → GeneralAssistantAgent 调用千问（Spring AI ChatClient）
       → 回写 Goal: SUCCEEDED / FAILED（含 summary）
```

聊天请求会作为 Goal 留存，可通过 `/api/harness/goals` 查询历史记录。