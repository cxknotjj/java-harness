# DeerFlow 设计思想与架构

> 基于字节跳动开源的 **DeerFlow**（Deep Exploration and Efficient Research Flow，深度探索与高效研究流）—— 一个开源的 **Super Agent Harness**（超级智能体运行框架）。
> 本文梳理其项目定位、总体架构、核心设计思想，以及落地这些思想的实施措施。

***

## 一、项目定位

DeerFlow 官方定位是 **Super Agent Harness**，可拆成三层理解：

1. **框架的框架（Harness）**：不是给终端用户用的聊天机器人，而是给开发者构建 Agent 应用提供的基础设施层。它负责编排（orchestration）、执行（execution）、隔离（isolation）、持久化（persistence），具体业务逻辑由开发者通过 Skills 配置。
2. **让 AI 真正动手做事**：不是"LLM 建议、人类执行"，而是 Agent 拥有自己的"电脑"——读写文件、执行 Bash、生成多页报告、并行调度子 Agent，任务可长达数分钟到数小时。
3. **聚焦复杂长任务**：官方明确不建议用于日常聊天/轻量交互；其设计目标是最小化复杂任务的执行门槛，最大化任务完成率与输出质量。

### 版本演进（v1 → v2）

| 维度        | DeerFlow v1       | DeerFlow v2                                |
| --------- | ----------------- | ------------------------------------------ |
| 架构        | 固定 5 节点多 Agent 拼接 | LangGraph 1.0 + LangChain 原生重构（完全重写，不共享代码） |
| 代码执行      | 无 Docker 沙箱       | 完整 Docker 沙箱                               |
| 任务时长      | 分钟级               | 分钟到小时级长期运行                                 |
| Sub-Agent | 有限支持              | 动态生成 + 上下文隔离                               |
| 扩展方式      | 插件机制              | Markdown Skills 系统                         |
| 适用场景      | 研究报告生成            | 端到端复杂项目执行                                  |

***

## 二、总体架构

### 2.1 组件架构（多服务 + 统一入口）

```
                 ┌────────────┐
   浏览器 ──────▶│  Nginx     │ (统一反向代理入口, 2026)
                 │  2026      │
                 └─────┬──────┘
         ┌─────────────┼─────────────┐
         ▼             ▼             ▼
   ┌──────────┐  ┌──────────┐  ┌──────────────┐
   │ Frontend │  │ Gateway  │  │ Provisioner  │
   │ Next.js  │  │  API     │  │ (可选, 8002) │
   │  3000    │  │  8001    │  └──────────────┘
   └──────────┘  └──────────┘
                      │ 内嵌 LangGraph 兼容 Agent 运行时
```

- **Gateway API（8001）**：FastAPI 应用 + 内嵌 LangGraph 兼容的 Agent 运行时（`RunManager` + `run_agent()` + `StreamBridge`），通过路由暴露 models/mcp/memory/skills/uploads/threads/artifacts/agents/suggestions/channels 等 REST 端点。Nginx 将 `/api/langgraph/*` 重写转发到 Gateway 原生 `/api/*`。
- **Frontend（3000）**：Next.js Web 界面。
- **Nginx（2026）**：统一反向代理入口。
- **Provisioner（8002，可选）**：仅在沙箱配置为 provisioner/Kubernetes 模式时启动。

### 2.2 后端代码结构（核心运行时）

```
backend/packages/harness/deerflow/
├── agents/          # LangGraph Agent 系统
│   ├── lead_agent/  # 主导 Agent（工厂 + 系统提示词）
│   ├── middlewares/ # 中间件链
│   ├── memory/      # 记忆抽取、队列、提示词
│   └── thread_state.py  # ThreadState 状态 schema
├── sandbox/         # 沙箱执行系统（local 提供方 / 抽象接口 / 工具 / 生命周期中间件）
├── subagents/       # 子智能体委派（builtins 通用/ bash / executor 后台执行 / registry）
├── tools/builtins/  # 内置工具（present_files、ask_clarification、view_image 等）
├── mcp/             # MCP 集成（工具、缓存、客户端）
├── models/          # 模型工厂（thinking / vision 支持）
├── skills/          # 技能发现、加载、解析
├── config/          # 配置系统（应用、模型、沙箱、工具等）
├── community/       # 社区工具（搜索/抓取/图像搜索/AIO 沙箱）
└── reflection/      # 动态模块加载（resolve_variable / resolve_class）
```

***

## 三、核心设计思想

### 3.1 Harness：编排、执行、隔离、持久化四件事

DeerFlow 把 Agent 应用最繁琐、最难做对的基础设施抽象成统一底座：

- **编排**：LangGraph 图引擎管理有状态的工作流与 Agent 生命周期；
- **执行**：Agent 真正在沙箱里跑代码、跑命令；
- **隔离**：每个任务在独立线程（Thread）+ 独立沙箱中运行，会话间零污染；
- **持久化**：线程状态、产物（artifacts）、长期记忆跨会话保留。

### 3.2 "不描述，而是动手"：执行优先

传统 ReAct 是"思考 → 调用工具 → 返回结果"的回合制。DeerFlow 让 Agent 直接拥有文件系统与终端：写脚本就落到磁盘、`pip install` 就真安装、起服务就能 curl。开发者角色从"照着 AI 指令敲键盘"变为"审批 Agent 的产出"。

### 3.3 Lead Agent + Sub-Agent：像资深工程师一样委派

两级智能体：

- **Lead Agent（总指挥）**：接收指令、把复杂任务拆成可并行子任务、动态生成 Sub-Agent、分配任务、汇总结果、质量把关；
- **Sub-Agent（专业执行者）**：每个拥有**独立上下文窗口、独立工具集、独立沙箱、独立终止条件**，可并行执行。

依赖关系由 Lead Agent 跟踪（如"邮件子 Agent 等表格子 Agent 完成，但调研子 Agent 与两者并行"），本质上是"识别可并行工作 → 分配专家 → 管理依赖 → 聚合结果"。

### 3.4 隔离即安全：沙箱是能力的边界

在"给 Agent 一台电脑"的同时，用沙箱划定安全边界：真实文件系统 + Bash + 可配置网络 + 资源限制 + 持久状态。能力越大，隔离越必须。

### 3.5 上下文工程：克制而非堆料

- **渐进式加载 Skills**：只在任务需要时加载相关技能，保持上下文精简；
- **自动总结与卸载**：SummarizationMiddleware 在接近 token 上限时压缩上下文，已完成子任务的结果卸载到文件系统，支持长时间任务。

### 3.6 长期记忆：越用越懂

跨会话积累用户偏好、技术栈、重复工作流的知识，使系统随使用而更贴合用户。

### 3.7 不重复造轮子，站在巨肩上

DeerFlow 不自己造模型接口/工具标准/状态引擎，而是：

- **LangChain** 提供模型接口、工具标准、中间件基类；
- **LangGraph** 提供工作流调度与状态管理；
- DeerFlow 专注于企业级的安全隔离、高性能调度、声明式配置。
  模型层通过工厂支持任意 OpenAI 兼容 API（GPT/Claude/Gemini/DeepSeek/豆包），模型可互换，编排成为护城河。

### 3.8 声明式与模块化

技能、工具、子智能体、模型、MCP 服务器均可通过配置（`config.yaml` / `extensions_config.json`）与 Markdown 技能文件声明式接入，最大化可组合性。

***

## 四、实施这些思想的措施

### 4.1 Docker 沙箱（落地"隔离即安全"）

- 每个 Agent 运行在隔离 Docker 容器内：**真实文件系统**（非模拟）、**完整 Bash**、**网络隔离（可配置）**、**资源限制（CPU/内存/执行时长）**、**步骤间持久状态**；
- 抽象接口 `Sandbox` + 多提供方：本地文件系统提供方（local）用于开发，Docker/容器用于生产；
- 沙箱工具集：`bash`、`ls`、`read_file`、`write_file`、`str_replace` 等，供 Agent 读写与执行。

### 4.2 每线程隔离与虚拟路径映射（落地"零污染"）

```
/mnt/user-data/workspace → backend/.deer-flow/threads/{thread_id}/user-data/workspace
/mnt/user-data/uploads   → backend/.deer-flow/threads/{thread_id}/user-data/uploads
/mnt/user-data/outputs   → backend/.deer-flow/threads/{thread_id}/user-data/outputs
/mnt/skills              → deer-flow/skills/（技能库）
```

- 每个线程独立目录，保证数据隔离；
- **路径遍历防护**：用严格正则校验 thread id，防止目录穿越攻击；
- **资源限制**：执行时间/内存/CPU 上限，防资源滥用。

### 4.3 ThreadState 扩展状态（落地"持久化"）

在 LangGraph `AgentState` 之上扩展专有字段，跟踪完整会话：

```python
class ThreadState(AgentState):
    messages: list[BaseMessage]   # 核心消息状态
    sandbox: dict                 # 沙箱环境信息
    artifacts: list[str]          # 生成的文件路径
    thread_data: dict             # 路径配置 {workspace, uploads, outputs}
    title: str | None             # 自动生成的对话标题
    todos: list[dict]             # 任务跟踪（计划模式）
    viewed_images: dict           # 视觉模型图像数据
```

### 4.4 中间件链（落地"请求处理管线"）

每个 Agent 请求按序经过中间件，各自职责单一：

- `ThreadDataMiddleware`：初始化工作区/上传/输出目录路径
- `UploadsMiddleware`：处理上传文件并注入消息
- `SandboxMiddleware`：获取沙箱执行环境
- `SummarizationMiddleware`：达到 token 上限时上下文压缩
- `TitleMiddleware`：自动生成对话标题
- `TodoListMiddleware`：计划模式下任务跟踪
- `ViewImageMiddleware`：支持视觉模型处理图像
- `ClarificationMiddleware`：处理用户澄清请求

### 4.5 子智能体系统（落地 Lead/Sub-Agent 编排）

- `subagents/registry.py`：Agent 注册表，动态生成与路由；
- `subagents/executor.py`：后台执行引擎，支撑并行子任务；
- `subagents/builtins/`：内置通用与 Bash 子 Agent；
- 每个 Sub-Agent 独立上下文/工具/沙箱/终止条件，结果由 Lead Agent 聚合。

### 4.6 Skills 技能系统（落地"渐进式加载"）

- 每个技能是一个**结构化 Markdown 文件**（定义工作流、最佳实践、资源引用）；
- 目录按能力分模块：`research`、`report-generation`、`slide-creation`、`web-page`、`image-generation` 等；
- 仅当任务需要时才加载对应技能，保持上下文精简；`skills/` 模块负责发现、加载与解析。

### 4.7 记忆系统（落地"长期记忆"）

- `agents/memory/`：记忆抽取（memory extraction）+ 队列（queue）+ 提示词；
- 跨会话积累用户偏好与技术栈知识；与 SummarizationMiddleware 配合卸载中间结果。

### 4.8 MCP 集成（落地"工具生态标准化"）

- 通过 **Model Context Protocol（MCP）** 标准化接入第三方工具；
- 支持三种传输：`stdio`、`SSE`、`streamable_http`；
- `mcp/` 模块提供工具、缓存、客户端；`extensions_config.json` 声明式配置 MCP 服务器与技能。

### 4.9 模型工厂（落地"模型可互换"）

- `models/` 工厂统一创建模型，支持 **thinking（推理）** 与 **vision（视觉）** 能力；
- 通过 `config.yaml` 的 `models:` 列表配置多提供商（OpenAI 兼容 / DeepSeek 思考模型等），运行时按名称解析。

### 4.10 统一运行生命周期（落地"调度复用"）

- 计划任务（scheduler）只决定**何时**执行，但必须复用 Gateway 现有的 `RunManager + run_agent() + StreamBridge` 运行路径，禁止引入并行执行栈，保证状态与流式行为一致。

### 4.11 SSE 流式（落地"实时反馈"）

- Agent 思考/执行过程通过 SSE 实时推送到前端，任务虽长但用户始终可见进展。

***

## 五、关键设计取舍

| 取舍    | DeerFlow 的选择                | 理由                    |
| ----- | --------------------------- | --------------------- |
| 执行方式  | 沙箱内真实执行                     | 提升完成率，Agent 可失败-调试-重试 |
| 隔离粒度  | 每线程独立沙箱                     | 零污染 + 安全边界            |
| 上下文策略 | 渐进加载 + 自动总结                 | 控制 token 成本，支撑长任务     |
| 依赖底座  | LangChain / LangGraph       | 复用成熟标准，专注企业级封装        |
| 扩展方式  | Skills(Markdown) + MCP + 配置 | 声明式、可组合、低门槛           |
| 运行路径  | 单一 Gateway 运行生命周期           | 状态一致，避免多套执行栈漂移        |

***

## 参考

- DeerFlow 官方仓库：<https://github.com/bytedance/deer-flow>
- AGENTS.md（架构与开发指南）：<https://github.com/leoredfish/deer-flow/blob/main/backend/AGENTS.md>
- 社区技术解析（Juejin、CSDN 等）与本仓库检索结果

