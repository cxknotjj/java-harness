<div align="center">

[简体中文](README.md) | [English](README_EN.md)

</div>

<div align="center">

# ☕ javaHarness

**An AI Agent orchestration framework built on Spring AI — a goal-driven, multi-agent execution harness**

[![Java](https://img.shields.io/badge/Java-17-007396?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.x-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.x-6DB33F?logo=spring&logoColor=white)](https://spring.io/projects/spring-ai)
[![Graph](https://img.shields.io/badge/graph--core-1.1.2.2-orange)](https://github.com/alibaba/spring-ai-alibaba)
[![MySQL](https://img.shields.io/badge/MySQL-Flyway%20Managed-4479A1?logo=mysql&logoColor=white)](https://www.mysql.com/)
[![Tests](https://img.shields.io/badge/tests-262%20passing-brightgreen?logo=junit5&logoColor=white)](#-running-tests)
[![Docker](https://img.shields.io/badge/sandbox-Docker%20Isolated-2496ED?logo=docker&logoColor=white)](#-prerequisites)

*Simple questions answered directly · Complex tasks orchestrated across agents · Fully streaming, end to end*

</div>

---

## 📑 Table of Contents

- [✨ Feature Highlights](#-feature-highlights)
- [🏗️ Architecture Overview](#%EF%B8%8F-architecture-overview)
- [🧰 Tech Stack](#-tech-stack)
- [🚀 Quick Start](#-quick-start)
- [🎮 CLI Usage](#-cli-usage)
- [🌐 REST API](#-rest-api)
- [📡 SSE Streaming Protocol](#-sse-streaming-protocol)
- [🔁 Resume from Checkpoint](#-resume-from-checkpoint)
- [🔌 Multi-Model & Multi-Provider](#-multi-model--multi-provider)
- [📁 Project Structure](#-project-structure)
- [🧪 Running Tests](#-running-tests)
- [🙏 References & Acknowledgements](#-references--acknowledgements)

## ✨ Feature Highlights

| | Feature | Description |
|---|---|---|
| 🧠 | **Smart Routing** | An upfront LLM judge decides SIMPLE / COMPLEX: small talk and Q&A get answered directly, only complex tasks enter orchestration — no wasted tokens |
| 🕸️ | **Multi-Agent Orchestration** | StateGraph "Lead decomposition → experts in parallel → aggregation"; subtasks are decomposed by difficulty (at most 4, no padding) |
| 👨‍👩‍👧‍👦 | **Expert System** | Four expert roles — researcher / coder / analyst / writer — configured from the database; lead assigns each subtask to the right expert |
| 📺 | **True Streaming** | Token-by-token SSE push with typewriter effect, plus real-time progress events for every orchestration stage (orchestration / decomposition / subtasks / aggregation) |
| 🧵 | **Thread-Pool Governance** | Background goals run in a managed bounded pool (capacity cap + fast-fail to FAILED when full); streaming dispatch uses a separate pool so the two never starve each other |
| 🛡️ | **Sandbox Isolation** | Model-generated code/commands run inside Docker containers with zero host exposure; tools are assigned per expert under least privilege |
| 💾 | **Session Memory** | Multi-turn context assembled automatically: filtering / token-budget truncation / role normalization |
| 🔁 | **Resume from Checkpoint** | graph-core checkpoints persisted to MySQL; after an interruption, `/resume` continues from the breakpoint without re-running completed nodes |
| 🧮 | **Call Observability** | Every LLM call is logged: latency / tokens / outcome, queryable per session |
| 🖥️ | **Claude Code-style CLI** | In-place spinner refresh, tool-call lines, turn summaries — a terminal experience modeled after Claude Code |

## 🏗️ Architecture Overview

```mermaid
flowchart TD
    A[🖥️ CLI / REST Request] --> B[ChatController<br/>Harness Shell Entry]
    B --> C{🧭 RouteJudge<br/>LLM decides SIMPLE / COMPLEX}
    C -->|SIMPLE| D[⚡ GeneralAssistantAgent<br/>Single call · token-by-token streaming]
    C -->|COMPLEX| E[🕸️ MultiAgentGraphAgent<br/>StateGraph Orchestration]
    E --> F[🧩 Lead Decomposition<br/>Max 4 subtasks · no padding]
    F --> G1[🔍 researcher]
    F --> G2[💻 coder]
    F --> G3[📊 analyst]
    F --> G4[✍️ writer]
    G1 & G2 & G3 & G4 --> H[📌 Aggregator<br/>Typewriter output of the final answer]
    D --> I[(🗄️ Goal State + Session Memory<br/>+ LLM Call Observability)]
    H --> I
    I --> J[📤 Unified Exit<br/>Sync JSON / SSE Streaming]
```

> [!TIP]
> For data-flow details see [`docs/data-flow.md`](./docs/data-flow.md) (Chinese), for the roadmap see [`docs/HARNESS_TODO.md`](./docs/HARNESS_TODO.md) (Chinese), and for the full test landscape see [`docs/functional-testing.md`](./docs/functional-testing.md) (Chinese).

## 🧰 Tech Stack

| Layer | Technology | Description |
|---|---|---|
| 🏛️ Framework | Spring Boot 3.5.14 | Application skeleton, DI, REST, auto-configuration |
| 🤖 AI Access | Spring AI 1.1.4 + `spring-ai-starter-model-openai` | OpenAI-compatible access to multiple providers (DashScope / DeepSeek); `Registry` pattern routes by model |
| 🕸️ Graph Orchestration | `spring-ai-alibaba-graph-core` 1.1.2.2 | StateGraph multi-agent orchestration + lifecycle-hook progress events + checkpoint-based resumption |
| 📦 Sandbox | `spring-ai-alibaba-sandbox` 1.1.2.2 | Container-level tool execution isolation (agentscope-runtime): Python/Shell/file + browser; requires local Docker |
| 🔌 MCP | `spring-ai-starter-mcp-client` + `server-webmvc` (SDK pinned to 0.17.0) | Client connects to external tools across multiple servers (lazy + failure isolation); server exposes the `/mcp` endpoint over Streamable-HTTP |
| 📚 RAG | `spring-ai-pgvector-store` + PostgreSQL (pgvector) + DashScope text-embedding-v4 | Knowledge-base vector search: incremental ingestion + retrieval enhancement on both paths (optional dependency; an unavailable PostgreSQL never blocks startup) |
| 🗄️ ORM | MyBatis-Plus 3.5.7 | CRUD for `goal` / `session` / `session_messages` / `agent` / `model_provider` |
| 🛫 Schema | Flyway | Migrations run automatically at startup — no manual table creation |
| 🖥️ CLI | In-house `ChatCli` + OkHttp 4.12 | Standalone pure HTTP client process; SSE parsing + terminal rendering |
| ✅ Validation / JSON | Jakarta Validation / Jackson | Parameter validation, DTO serialization, SSE meta parsing |
| 🛠️ Build | Maven (in-project repo `.mvn-repo`) | See [Quick Start](#-quick-start) |

## 🚀 Quick Start

### 📋 Prerequisites

| Dependency | Required | Notes |
|---|---|---|
| ☕ JDK | ✅ | 17+ |
| 🛠️ Maven | ✅ | 3.8+ (in-project settings; no global configuration needed) |
| 🗄️ MySQL | ✅ | `harness` database; Flyway creates all tables at startup |
| 🐳 Docker Desktop | ⚠️ For sandbox | Container isolation for Python/Shell/browser tools; without Docker only sandbox-class tools are unavailable, everything else works (pre-pull images, see `docs/TECH_STACK.md`) |
| 🔑 API Key | 🔄 Optional | DashScope (Qwen) / DeepSeek; the app starts without keys — model calls return `invalid_api_key` |

### ⚡ One-Click Start

> [!TIP]
> Three launcher scripts — pick one (**never run two at once**, port 8080 would clash):
> - **`run-wsl.bat`** (recommended on Windows): double-click to enter WSL automatically — compile → start the service in the background (log at `/tmp/javaHarness-server.log`) → this window becomes the CLI once ready
> - **`run.sh`** (WSL terminal): `./run.sh` runs the full flow — compile → open a new window for the service → poll until ready in this terminal → enter the CLI; subcommands `server / stop / cli / build / test`
> - **`run-win.bat`** (native Windows): for a Windows-side checkout with Windows JDK/Maven

### 🔧 Manual Start

**1️⃣ Start the main service**

```powershell
mvn -s .mvn/settings.xml spring-boot:run
```

**2️⃣ In another terminal, start the CLI**

```powershell
mvn -s .mvn/settings.xml exec:java
```

**3️⃣ Or chat directly over REST (no CLI needed)**

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"你好"}'
```

**4️⃣ (Optional) Configure real API keys**

Any one of the following, then restart the service (the app also starts with no keys — model calls fail with a 401 placeholder-key error):

```powershell
# Option 1: system-level environment variables (recommended; global for new windows; WSL sessions need WSLENV passthrough)
setx QWEN_API_KEY "sk-your-key"      # DashScope (Qwen)
setx DEEPSEEK_API_KEY "sk-your-key"  # DeepSeek
setx WSLENV "QWEN_API_KEY/u:DEEPSEEK_API_KEY/u"   # pass into WSL (needed when running Linux-side)

# Option 2: repo-root .env.local (auto-sourced by run.sh / run-wsl.bat; gitignored)
#   QWEN_API_KEY=sk-your-key
#   DEEPSEEK_API_KEY=sk-your-key

# Option 3: current terminal session only
$env:QWEN_API_KEY = "sk-your-key"    # Windows PowerShell; use export QWEN_API_KEY=... in WSL
```

## 🎮 CLI Usage

The CLI is a pure HTTP client (**listens on no port**) and talks to the main service over REST:

```text
你> 你是谁
千问> 我是通义千问，一个AI助手...
```

| Command | Effect |
|---|---|
| Type any text | Chat with the current agent (general by default); multi-turn memory carries over |
| `/new [name]` | 🆕 Create a session and switch to it (the old one is kept) |
| `/agent <id>` | 🎭 Switch to an agent (primary key of the `agent` table); `/agent` shows current; `/agent off` restores smart routing |
| `/resume <goalId>` | 🔁 Resume an interrupted orchestration from its checkpoint (goalId appears in the session info at the end of each turn) |
| `/help` / `/exit` | ❓ Help / 🚪 Exit |

## 🌐 REST API

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/chat` | 💬 Sync chat: `{"message":"你好","agentId":1}` |
| `POST` | `/api/chat/stream` | 📺 Streaming chat (SSE): same request body, token-by-token push |
| `POST` | `/api/chat/resume?goalId=` | 🔁 Resume an interrupted orchestration; response format identical to `/stream` |
| `GET` | `/api/harness/agents` | 🧩 Registered agents |
| `GET` | `/api/harness/goals` | 🎯 Goals (with chat records) and statuses |
| `GET` | `/api/harness/goals/{id}` | 🎯 Query a single goal's status |
| `POST` | `/api/harness/submit?agent=general&objective=...` | 📤 Submit an async goal |
| `POST` | `/api/harness/sessions` | 🆕 Create a session (optional `name`), returns sessionId/name |
| `GET` | `/api/llm-calls?sessionId=&limit=` | 🧮 LLM call observability: latency / tokens / outcome (default 50) |
| `POST` | `/api/knowledge/sync` | 📚 Incremental ingestion: scan `knowledge/`, re-embed mtime-changed docs |
| `GET` | `/api/knowledge/documents?page=&size=` | 📚 Paged ingestion ledger |
| `GET` | `/api/knowledge/search?q=` | 📚 Debug retrieval: hit chunks & scores (not injected into prompts) |
| `DELETE` | `/api/knowledge/documents/{name}` | 📚 Delete a knowledge document (vector chunks + ledger) |

> [!NOTE]
> `agentId` is optional (primary key of the `agent` table): when absent, the default agent (general) handles the request.
>
> Knowledge endpoints require `app.knowledge.enabled=true` (default) plus pgvector / embedding config; they return 503 when disabled.

### 📚 Knowledge Base QA (RAG)

Drop documents into the `knowledge/` directory (`.md` / `.txt`, optional front-matter `title:`); after ingestion both paths automatically retrieve & inject before answering:

```bash
mkdir -p knowledge && cp your-doc.md knowledge/
curl -X POST http://localhost:8080/api/knowledge/sync          # incremental ingestion (only mtime-changed docs re-embedded)
curl 'http://localhost:8080/api/knowledge/search?q=deploy'     # debug retrieval hit view
```

Answers carry `【Source N】` inline citations, the CLI prints a "Sources:" footer, and `meta.sources` / `ChatResponse.sources` expose structured provenance (doc name / title / score). Configuration (top-k / min score / injection budget) lives under `app.knowledge.*` in `application.yaml`.

#### 🔍 RAG Trigger Logic

RAG is never triggered by explicit commands — it is a **bypass check before every prompt assembly**; unmet conditions degrade silently with zero side effects on the main flow.

Trigger decision flow:

```mermaid
flowchart TD
    A[User request<br/>Path A direct answer / Path B orchestration node] --> B["AgentRequestSpecFactory<br/>before assembling system prompt"]
    B --> C{"app.knowledge.enabled?"}
    C -->|false| X[Silent skip<br/>zero side effects]
    C -->|true| D{"Agent role in skip list?<br/>aggregator: its material is subtask results"}
    D -->|yes| X
    D -->|no| E{"User text length ≥<br/>min-query-chars (8)?"}
    E -->|no| X
    E -->|yes| F["Vector search: user text → DashScope embedding<br/>→ pgvector cosine top-k (4)"]
    F --> G{"Hits with score ≥<br/>min-score (0.5)?"}
    G -->|no| X
    G -->|yes| H["Accumulate tokens per hit:<br/>hits beyond context-budget (3000) are truncated"]
    H --> I["Render knowledge block with 【Source N】<br/>append to system prompt"]
    I --> J["Answer: inline 【Source N】 citations<br/>+ meta.sources + CLI source footer"]
```

Retrieval injection sequence:

```mermaid
sequenceDiagram
    participant U as User
    participant F as AgentRequestSpecFactory
    participant R as KnowledgeRetriever
    participant S as KnowledgeService
    participant E as DashScope Embedding
    participant V as pgvector
    participant L as LLM
    U->>F: Request (Path A / Path B node)
    F->>R: buildKnowledgeBlock(agent, sessionId, user)
    R->>R: enabled / role / query-length short-circuits
    R->>S: search(query)
    S->>E: Embed query
    S->>V: cosine similarity top-k
    V-->>S: Hit chunks
    S-->>R: List<KnowledgeHit>
    R-->>F: Knowledge block (【Source N】 within budget) or null
    F->>L: system prompt (+ knowledge block)
    L-->>U: Answer with inline citations + meta.sources
```

Trigger conditions (fully config-driven, tune via `application.yaml`):

| Trigger condition | Config key | Current value | When unmet |
|---|---|---|---|
| Knowledge base enabled | `enabled` | true | silent skip |
| Role not in skip list | — (aggregator skipped by policy) | aggregator | silent skip |
| Query length threshold | `min-query-chars` | 8 | silent skip |
| Hit score threshold | `min-score` | 0.5 | drop the hit |
| Within injection budget | `context-budget` | 3000 | truncate budget-exceeding hits |

## 📡 SSE Streaming Protocol

Response `Content-Type: text/plain` (an SSE-style line protocol; each Flux element is its own line, with `event:` + `data:` pairs). Example event stream:

```text
event: progress
data: {"stage":"编排","detail":"开始拆解复杂目标…"}
event: progress
data: {"stage":"拆解","detail":"4 个子任务已就绪"}
event: token
data: chunk-1
event: token
data: chunk-2
...
data: [DONE]
event: meta
data: {"sessionId":"9","newSession":true,"goalId":null,"status":"SUCCEEDED","error":null}
```

| Event | Meaning |
|---|---|
| 📺 `event: token` | Text chunks generated by the model (pushed token by token; in-line newlines are escaped so each event stays a single line) |
| 📣 `event: progress` | Real-time orchestration progress (orchestration / decomposition / subtask done / aggregation); **not** written into session memory |
| 🏷️ `event: meta` | Session info at the end of a turn: `sessionId` / `newSession` / `goalId` / `status`; on failure `status=FAILED` plus an `error` field; when knowledge hits exist, `sources` carries them (doc name / title / relevance) |
| ⚠️ `event: error` | In-stream error message |

> [!IMPORTANT]
> - Each SSE event is emitted as a single atomic pair (`event:` + `data:` are never interleaved by other events)
> - `[DONE]` is sent after everything has been pushed
> - Use `curl -N` to watch chunks arrive one by one

## 🔁 Resume from Checkpoint

Complex orchestration (the COMPLEX path) is built on the graph-core checkpoint system (`MysqlSaver` creates and persists tables automatically, `threadId=goalId`):

| Interruption Point | Resume Behavior |
|---|---|
| ✅ Orchestration already finished | Zero LLM calls; the final answer is replayed directly |
| ⏸️ Subtask batch done, aggregation interrupted | Only aggregation re-runs (typewriter output); subtask results are reused |
| ⏹️ Earlier (e.g. mid subtask batch) | Completed nodes are not re-run; only the gap is executed |
| 🚫 No checkpoint at all | Fast fail: reports that the goal never went through the complex path |

```bash
# Via API
curl -N -X POST "http://localhost:8080/api/chat/resume?goalId=<goalId>"

# Via CLI
/resume <goalId>
```

> [!NOTE]
> Missing `goal` returns 400; still running returns 409; with no checkpoint an `error` event is emitted in-stream.

## 🔌 Multi-Model & Multi-Provider

**Database-driven multi-agent + multi-provider** — adding providers takes zero code:

```mermaid
flowchart LR
    A[agent table<br/>agent_name / model / prompt] -->|agent.model references| B[model_provider table<br/>model → provider / api_url]
    B --> C[ChatClientRegistry<br/>Resolve provider ChatClient by model]
```

- 🧩 **Agents** (`agent` table): one row per agent (`agent_name`/`model`/`prompt`). Seed rows: `general`/`deepseek` (chat), `multi-agent` (orchestrator), `lead` (decomposer), `aggregator`, and the experts `researcher`/`coder`/`analyst`/`writer`
- 🗺️ **Model mapping** (`model_provider` table): adding a model/provider = adding one row (`status=1`) and restarting; `status=0` disables it → falls back to the default DashScope client
- 🧭 **Routing**: a request carrying `agentId` maps to `agentName` for routing; misses fall back to the default `general`

> [!TIP]
> **Add a third-party provider (e.g. Moonshot, OpenRouter) with zero code**:
> 1. Set the environment variable `MOONSHOT_API_KEY` (convention: `<PROVIDER in upper case>_API_KEY`)
> 2. Add a row to `model_provider`: `INSERT INTO model_provider(model, provider, api_url, status) VALUES('kimi-k2','moonshot','https://api.moonshot.cn/v1',1);`
> 3. Restart to take effect
>
> Alternatively, map keys explicitly in `application.yaml` under `app.providers.<provider>.api-key` (takes priority over the environment-variable convention; existing variable names stay compatible).

> [!WARNING]
> For security, API keys are never stored in the database. Resolution rules (convention over configuration):
> 1. `app.providers.<provider>.api-key` (explicit yaml mapping, highest priority)
> 2. `<PROVIDER in upper case>_API_KEY` environment variable (fallback by convention, e.g. `QWEN_API_KEY`, `DEEPSEEK_API_KEY`)
>
> Key **loading channels**: system-level environment variables (Windows side + `WSLENV` passthrough into WSL) > repo-root `.env.local` (auto-sourced by the launch scripts, gitignored) > current-session `export`; resolution priority is unaffected by the channel.

## 📁 Project Structure

Classic layered architecture (Controller → Service → Mapper/Entity), with the domain model grouped under the `domain` parent package:

<details>
<summary><b>📂 Click to expand the full directory tree</b></summary>

```text
src/main/java/com/dark/javaHarness/
├── JavaHarnessApplication.java   # Spring Boot entry (@MapperScan points to the mapper package)
├── controller/                   # Presentation layer: REST endpoints + SSE streaming
│   ├── ChatController.java       # Chat endpoints (/api/chat, /stream, /resume, /goal-status)
│   ├── HarnessController.java    # Management endpoints (agents / submit / goals / sessions / session-bound agent)
│   ├── ProviderAdminController.java  # Model-mapping management (/api/providers: list & hot-refresh add)
│   └── LlmCallController.java    # LLM call observability queries (/api/llm-calls)
├── service/                      # Business layer (interfaces + impl/)
│   ├── AgentService / GoalService / SessionService / ChatService  # Orchestration, goals, session memory, chat use cases
│   ├── RouteJudge.java           # Main-agent routing decision (SIMPLE / COMPLEX)
│   ├── AgentConfigProvider.java  # Runtime config from the agent table (routing map)
│   ├── ProviderAdminService.java # model_provider mapping management (hot refresh on add)
│   └── impl/                     # Implementations (AgentServiceImpl / ChatServiceImpl / LlmRouteJudge / LlmCallRecorder etc.)
├── advisor/                      # Spring AI Advisor interceptors (cross-cutting agent-flow management)
│   ├── ContextAssemblingAdvisor.java  # Context assembly: filter / token-budget truncation / role normalization
│   └── PromptBudgetAdvisor.java  # Prompt section budgets (history / user / tool-result truncation)
├── config/                       # Application configuration
│   ├── GoalExecutorConfig.java   # Execution pools: goal-exec- background goal pool + mvc-async- MVC async slot
│   ├── ContextBudgetProperties.java  # Unified context budget config (app.context.*; yaml is the single source of numbers)
│   ├── KnowledgeProperties.java  # RAG knowledge-base config carrier (app.knowledge.*)
│   ├── KnowledgeConfig.java      # Knowledge-base wiring: vector datasource / embedding model / PgVectorStore (conditional + lazy connections)
│   ├── PrimaryDataSourceConfig.java  # Explicit primary (MySQL) datasource declaration (@Primary; Flyway/MyBatis ownership with multiple datasources)
│   ├── MybatisPlusConfig.java    # MyBatis-Plus configuration (pagination etc.)
│   └── agent/                    # Agent configuration & assembly
│       ├── ChatAgentConfig.java      # Registers agent beans + graph-core checkpoint store (MysqlSaver)
│       ├── ChatClientFactory.java    # Builds OpenAI-compatible ChatClients per provider (Registry pattern)
│       ├── ChatClientRegistry.java   # Model-name → ChatClient registry (hot-refreshable)
│       └── ThinkingSwitchChatModel.java  # Injects thinking switch per model_provider.disable_thinking
├── prompt/                       # Prompt assembly pipeline (shared by both paths)
│   ├── PromptAssembler.java      # Five-section system prompt (role / tool index / discipline / output / skill)
│   ├── MemoryPolicy.java         # Per-role session-memory injection matrix
│   ├── SkillManager.java / SkillRepository.java  # Markdown skill library assembly (injected on demand)
│   ├── ToolLazyManager.java      # Two-phase lazy tool-schema loading (lightweight index → expand_tool)
│   └── PromptSection.java / SkillSectionProvider.java  # Section model and skill extension point
├── mapper/                       # Data access: MyBatis-Plus mappers
│   └── AgentMapper / GoalMapper / SessionMapper / SessionMessageMapper / ModelProviderMapper / LlmCallLogMapper
├── domain/                       # Domain model (parent package)
│   ├── Goal.java                 # Goal + status (PENDING/RUNNING/SUCCEEDED/FAILED)
│   ├── AgentConfig.java          # Agent runtime config (model + prompt), from the agent table
│   ├── RouteDecision.java        # Routing decision enum (SIMPLE / COMPLEX)
│   ├── LlmCallLog.java           # Observability record of one LLM call (latency/tokens/outcome)
│   ├── dto/                      # Transfer objects (ChatRequest/ChatResponse/SseMeta/pagination etc.)
│   └── entity/                   # DB entities (agent / goal / session / model_provider / llm_call_log tables)
├── enums/                        # Enums & shared constants: GoalStatus, AgentConstants, SseProtocol
├── exception/                    # Global exception handling (@RestControllerAdvice, uniform {code, message})
├── agent/                        # Agent abstractions, orchestration & LLM calls
│   ├── Agent.java / AgentRegistry.java    # Agent interface and registry
│   ├── GeneralAssistantAgent.java  # Path A: single-model chat (true token-by-token stream)
│   ├── MultiAgentGraphAgent.java   # Path B: StateGraph orchestration facade (lead → parallel subtasks → aggregation + checkpoint resume)
│   ├── AgentChatCaller.java        # LLM call wrapper (tool loop, hallucinated-tool fallback, BudgetLedger budget accounting & breaking)
│   ├── AgentRequestSpecFactory.java  # Shared request-assembly factory (system / memory injection / tool decoration / output tier)
│   ├── LeadOutputParser.java       # Lead decomposition JSON parsing (subtask count + expert dispatch whitelist)
│   ├── OrchestrationBudget.java    # Orchestration budget ledger (AtomicLong shared accounting + degradation note)
│   ├── MultiAgentStreamPipeline.java  # Orchestration streaming pipeline (progress lines → SSE events, resume checkpoint selection)
│   ├── BranchProgressListener.java # graph-core lifecycle-hook sidecar (serializes parallel-branch completion events)
│   ├── LlmRetry.java               # LLM call retry policy
│   └── ProgressLine.java           # Progress line wire protocol (MARK+stage+SEP+detail) codec
├── cli/                          # CLI client (standalone process, pure HTTP to 8080)
│   ├── ChatCli.java              # Facade: main / chatLoop / command dispatch / turn execution
│   ├── ResumeStateStore.java     # /resume target persistence (state-file IO + tolerant parsing)
│   ├── input/TerminalInput.java  # Terminal input layer: JLine history/completion/paste; falls back to line reads without a TTY
│   ├── api/ChatApiClient.java    # OkHttp wrapper for chat / streaming / resume / provider / session endpoints (SSE parsing)
│   └── render/                   # Claude Code-style rendering
│       ├── TerminalRenderer.java # Facade: incremental streaming output + spinner collapse + tool-call lines
│       ├── MarkdownAnsiRenderer.java / Ansi.java  # Markdown line-level ANSI coloring
│       └── Spinner.java          # Stage progress spinner (in-place refresh)
└── tool/                         # Tools
    ├── WebTools.java             # Web fetch facade (fetchUrl: fetch + 30-min content cache + query-relevant clipping)
    ├── HtmlToMarkdown.java / ContentRelevance.java  # Noise removal / main-content Markdown extraction / query-intent clipping
    ├── SandboxToolProvider.java  # Container-level sandbox tools (Python/Shell/file + browser; bounded lazy init, graceful degradation)
    ├── McpToolProvider.java / McpConfigParser.java  # MCP tool access (multi-server lazy connect, failure isolation) / mcp-config.json parsing
    ├── McpServerTools.java       # Demo tools exposed by the in-process MCP server (Streamable-HTTP /mcp)
    ├── ToolAssignments.java      # Tool assignment table: per-expert toolsets (dual-channel injection, least privilege)
    ├── ToolCallBudget.java / ToolCallTracer.java    # Tool count/result hard budget / start-stop progress lines
    ├── TokenEstimator.java       # Project-wide token estimation standard
    └── DemoTools.java            # Demo toolset (time / calculator / weather)
```

</details>

> [!NOTE]
> **Layer responsibilities**: `controller` handles REST/SSE and carries no business logic; `service` orchestrates the core logic (interfaces separated from implementations); `mapper` / `domain.entity` handle database reads, writes and mapping.

## 🧪 Running Tests

Unit tests run on JUnit 5 + Mockito and need **no real database / network / API keys** (currently 327 test cases, all green):

```bash
mvn -s .mvn/settings.xml test
```

<details>
<summary><b>🔍 Click to expand the test coverage list</b></summary>

| Group | Tests | What it verifies |
|---|---|---|
| 🧭 Routing & sessions | `LlmRouteJudgeTest` `AgentServiceImplTest` `AgentConfigProviderTest` `SessionServiceImplTest` `GoalServiceImplTest` | SIMPLE/COMPLEX routing and malformed-JSON fallback, multi-agent routing with fallback, session & goal lifecycle |
| 🤖 Agent calls | `AgentChatCallerTest` `AgentChatCallerRetryTest` `LlmRetryTest` `GeneralAssistantAgentTest` | Tool loop with budget accounting & circuit breaking, hallucinated-tool fallback retry, retry policy, progressive token emission (anti fake-streaming regression) |
| 🕸️ Orchestration | `MultiAgentGraphAgentTest` `ProgressLineTest` | Orchestration closed loop, progress ordering (anti deadlock / lost events), expert dispatch whitelist, checkpoint resume (gaps filled / completed nodes reused), **partial budget degradation** (remaining subtasks skipped, aggregation keeps earlier results) |
| 🧩 Prompt assembly | `PromptAssemblerTest` `MemoryPolicyTest` `SkillManagerTest` `SkillRepositoryTest` `ToolLazyManagerTest` | Five-section assembly, per-role memory injection matrix, dynamic skill assembly, two-phase lazy tool-schema loading |
| 🎚️ Budget & observability | `ContextBudgetPropertiesTest` `ContextAssemblingAdvisorTest` `PromptBudgetAdvisorTest` `ToolCallBudgetTest` `ToolCallTracerTest` `LlmCallRecorderTest` | yaml binding with 0=unlimited semantics, context/section trimming, tool count & result budget, start-stop progress lines, call observability persistence |
| 🔌 Tools & MCP | `WebToolsTest` `SandboxToolProviderTest` `McpToolProviderTest` `ToolAssignmentsTest` | HTML→Markdown extraction and protocol whitelist, sandbox lazy-init degradation, MCP multi-server config parsing, least-privilege tool assignment with dedup |
| 📚 Knowledge base (RAG) | `MarkdownChunkerTest` `KnowledgeDocumentScannerTest` `KnowledgeServiceImplTest` `KnowledgeRetrieverTest` | Paragraph-aware chunking with overlap carryover, front-matter parsing & bad-file skip, incremental ingestion / delete / pagination / search (mocked VectorStore+Mapper), role skip / budget truncation / 【Source N】 rendering |
| 🖥️ CLI | `TerminalRendererTest` `TerminalInputTest` `ResumeStateStoreTest` | Markdown line coloring and incremental streaming output (anti ghost-repeat regression), degraded input without a TTY, resume-state read/write roundtrip |
| 🌐 API & infrastructure | `ChatControllerTest` `HarnessControllerTest` `ChatServiceImplTest` `GlobalExceptionHandlerTest` `ChatClientRegistryTest` `ChatClientFactoryTest` `ThinkingSwitchChatModelTest` `ProviderAdminServiceImplTest` `AgentRegistryTest` `ClientAbortLogFilterTest` `ModelQuotaExceptionTest` | REST contracts (streaming element-by-element / newlines), SSE contract & resume validation (400/409), uniform {code, message} errors, multi-provider registry with hot refresh, thinking switch |

</details>

> [!WARNING]
> `JavaHarnessApplicationTests` is a `@SpringBootTest` that tries to connect to local MySQL; running it standalone without a database may fail on connection (all other business tests are unaffected).

## 🙏 References & Acknowledgements

This project's design was inspired by the following excellent open-source projects/products — with thanks:

| Reference | What we borrowed |
|---|---|
| 🦌 [Deer-Flow](https://github.com/bytedance/deer-flow) (ByteDance) | The multi-agent orchestration paradigm — "Coordinator → Planner decomposition → experts in parallel → Reporter aggregation" and the researcher / coder / analyst / writer expert roles directly inspired this project's StateGraph orchestration and expert agent system |
| ⌨️ [Claude Code](https://github.com/anthropics/claude-code) (Anthropic) | The CLI terminal experience: in-place spinner refresh + collapsed completion archive, tool-call lines (`⏺ tool(args)` → `✓ duration`), diff `+green/-red` coloring, turn summaries and other interactions (`TerminalRenderer`) |
| 🐳 [DeepSeek](https://github.com/deepseek-ai) (deepseek-ai) | Agent toolset design: the capability split for web fetch (fetchUrl) and file/command tools, and the least-privilege idea of assigning tools per agent |

---

<div align="center">

**⭐ If this project helps you, please give it a Star!**

 Made with ☕ and ❤️ by javaHarness contributors

</div>
