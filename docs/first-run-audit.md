# 首次可用性走查报告（First-Run Audit）

> 走查日期：2026-09-11。方法：以「第一次打开产品、零配置、不了解内部术语」的目标用户为起点，
> 围绕核心任务「**让机器人端到端回答第一句话**」做逐步走查；实测在隔离环境执行（独立端口 18080 +
> 独立测试库 `harness_it`，dummy key、无 NapCat、无 Docker、无 PostgreSQL——完整复刻全新机器状态），
> 未触碰真实 8080 实例与既有配置，未执行真实授权/对外发送/真实 LLM 调用，实测后环境已清理。
> 本报告区分三类结论：**【观察】**=隔离环境实测所见；**【推断】**=代码/数据佐证的推论；**【未验证】**=需真实环境确认。

## 一、模块阶段判定

| 模块 | 阶段 | 说明 |
|---|---|---|
| 双路径对话（RouteJudge + 编排/直答） | **开发（可运行）** | 首请求即达 LLM，链路完整 |
| CLI / REST / SSE 契约 | **开发（可运行）** | SSE 事件序（progress→error→meta）清晰 |
| 参数校验与统一错误体 | **开发（可运行）** | 中文文案明确 |
| 会话 / Goal / 观测落库 | **开发（可运行）** | llm_call_log 实测可定位根因（观测能力的正面验证） |
| QQ 渠道（NapCat） | **开发（可运行）** | 默认开启与「可选依赖」定位冲突（见问题 5） |
| 知识库 RAG | **开发，反馈面未闭环** | 检索降级 ✓；sync 失败反馈被吞（问题 3） |
| 沙箱工具 | **开发，降级未生效** | Docker 缺失时假就绪（问题 4） |
| Web Search | **设计（未开始）** | HARNESS_TODO P1 待办 |
| 前端 / 容器化 / CI / Swagger / 接口鉴权 | **设计（未开始）** | HARNESS_TODO P3 |
| 编排智能化（重规划/依赖编排） | **设计（未开始）** | HARNESS_TODO P2 |

## 二、核心任务的准备条件三分类

| 类别 | 内容 | 现状 |
|---|---|---|
| **必须由用户决定/授权** | LLM API Key（QWEN_API_KEY，或改绑其他 provider 后对应 key）；一次性环境：JDK17+Maven+MySQL | Key 有 README 三方式指引 ✓；但种子 agent 绑错端点使「只配 QWEN」走不通（问题 1） |
| **可用合理默认值** | 端口 8080、模型档位、token 预算、超时、限频、Flyway 建库表 | 全部已有合理默认 ✓ |
| **可稍后补充（不应成为首启负担）** | PostgreSQL 知识库、NapCat/QQ 渠道、Docker 沙箱、MCP、skills、多 agent 定制 | PG 检索降级 ✓；但 NapCat 默认 enabled=true 违背此原则（问题 5）；沙箱假就绪误导（问题 4） |

## 三、问题清单（按对核心任务的影响排序）

### 问题 1 【观察·高】种子数据把默认 agent 绑到 DeepSeek 端点——只配 QWEN key 走不通

- **用户处境**：新用户按 README 快速开始只配置 `QWEN_API_KEY`（README 第 4 步只提 QWEN/DeepSeek，主推 QWEN）。
- **操作**：`curl POST /api/chat {"message":"你好"}`。
- **看到的反馈**：HTTP 200 + `status:FAILED` + 英文错误，`llm_call_log` 实证：route-judge→qwen3.8-27b(dashscope) 401 兜底 SIMPLE 后，general→**deepseek-v4-flash** 401。链路终止于 DeepSeek 鉴权失败。
- **为什么会卡住**：全新空库经迁移链（V1 种子 `qwen3.7-plus` → V4 改名 model_provider → V6 按 model 名回填 JOIN 不命中 → 后续迁移产生的绑定）最终落位 `general.model_provider_id=8`（deepseek-v4-flash）。README 的 key 指引与种子端点脱节——**这是首次使用走不通的根因**。
- **最小修改**（二选一，需决策）：
  - A. 新增迁移：仅当 `general`/`researcher` 绑定在 `provider='deepseek'` 行时改绑 `qwen3.7-flash` 行（存量库上用户自定义绑定的 general 会被此条件误改，需评估）；
  - B. 只修文档：README 快速开始明确「首次使用需检查 agent 表 general 行绑定的 model_provider 指向已配 key 的服务商」+ 提供一条核对 SQL。
- **验收**：全新库 + 仅配 QWEN_API_KEY → 首请求成功返回中文回答。

### 问题 2 【观察·高】LLM 鉴权失败的反馈无下一步指引

- **用户处境**：key 未配/配错，首次对话失败。
- **操作**：同步或流式首请求。
- **看到的反馈**：同步 `HTTP 200` + `{"status":"FAILED","error":"401 - {\"error\":{\"message\":\"Authentication Fails...\"}}"}`（上游英文原文）；流式 `event:error` + 同样英文原文。
- **为什么会卡住**：HTTP 200 让调用方以为成功；error 无中文引导；「配 QWEN_API_KEY 并重启」只存在于启动日志 WARN 与 README——不看日志的新用户无从下手。
- **最小修改**：错误映射层对 401/invalid_api_key 归一化，error 前缀追加中文指引（如「大模型鉴权失败：请配置对应服务商 API key 环境变量并重启服务，详见 README 快速开始」），上游原文保留其后；goal 表仍落原始错误。
- **验收**：零配置首请求的响应 error 首句为中文可执行指引。

### 问题 3 【观察·中】知识库 sync 在 PG 不可用时返回 500「服务器内部错误」

- **用户处境**：没装 PostgreSQL 的新用户看到 README 知识库章节，把文档放进 knowledge/ 后调 sync。
- **操作**：`POST /api/knowledge/sync`。
- **看到的反馈**：`{"code":500,"message":"服务器内部错误"}`——内部可读的 IllegalStateException（「向量库不可用」语义）被 GlobalExceptionHandler 兜底吞掉，与代码注释「同步报清晰错误」的设计意图脱节。
- **最小修改**：GlobalExceptionHandler 对该路径（或 IllegalStateException 总类）透出异常 message 作为 response message（保留 500 或改 503）。
- **验收**：PG 未配置时 sync 返回含「PostgreSQL 未就绪/未启用」语义的中文 message。

### 问题 4 【观察·中】Docker 缺失时沙箱假就绪并注册 12 个工具

- **用户处境**：无 Docker 的机器（README 标 Docker 为「沙箱必需」⚠️）。
- **操作**：启动应用后让 agent 执行代码。
- **看到的反馈**：启动日志 Docker 连接 ERROR 两条后紧跟 INFO「[sandbox] 容器级沙箱就绪: 执行类=2 只读=6 写入=4」——工具面注册成功；实际调用才会暴露深层 Docker 异常。与 README「无 Docker 时仅沙箱类工具不可用，其余功能正常」的承诺矛盾。
- **为什么会卡住**：降级设计（失败降级空工具面）未生效于「连接失败」分支，模型会持续尝试调用必失败的工具。
- **最小修改**：SandboxToolProvider 初始化时探测 Docker 可达性，不可达则降级空沙箱工具面 + WARN 明示「未检测到 Docker，沙箱类工具未启用（安装 Docker 后重启恢复）」。
- **验收**：无 Docker 启动日志不再出现「沙箱就绪」，且模型工具索引段不含沙箱工具。

### 问题 5 【观察·低】NapCat 默认开启使可选依赖变成首启必现告警

- **操作**：全新机器启动（napcat.enabled 默认 true，api-base-url 127.0.0.1:3000）。
- **看到的反馈**：自检 WARN 三连「get_login_info 调用异常（第 1/2 次）…启动自检失败：retcode=-1（QQ 发送将不可用，检查 NapCat/token）」。
- **认可点**：不阻断启动、文案有一定可读性。
- **卡点**：不用 QQ 渠道的新用户不知道这是可关闭的可选组件（无「设 napcat.enabled=false 可消除」的出口提示）。
- **最小修改**：README 快速开始加一行「不接入 QQ 渠道时在 application.yaml 设 napcat.enabled=false」；自检失败 WARN 尾部追加同一提示。
- **验收**：无 NapCat 全新启动后，用户能从告警或 README 一眼找到关闭方式。

### 问题 6 【推断·低】非法 agentId 静默回退默认 agent

- **操作**：`agentId=999`（不存在）请求 → 返回与默认请求相同的结构，无「agent 不存在」提示（AgentService 兼容兜底回退 general，有 javadoc 背书，行为本身合理）。
- **最小修改（可选）**：响应体增加 `agentResolved` 字段透出实际使用的 agent 名，让「回退」可观察。
- **验收**：传入无效 agentId 时响应可区分「用了哪个 agent」。

### 未验证项

- 配置真实 key 后的端到端对话（本次走查不执行真实 LLM 调用/对外发送）；按数据推断：仅配 QWEN key 时因问题 1 仍失败，双 key（QWEN+DEEPSEEK）可走通——与真实环境「能跑通」的现象吻合。
- CLI 端在服务端未启动/错误事件下的终端渲染细节（代码走查确认 ChatApiClient 抛 ApiException 含服务端 message，渲染文案未实测）。
- 真实用户测试未做：本报告为 AI 走查 + 隔离实测，不等于用户认可。

## 四、认可的已有设计（保持不动）

- 快速开始文档的环境要求分级（必需/沙箱/可选）与 key 三方式指引；
- 空参数校验反馈（`{"code":400,"message":"message 不能为空"}`，中文、准确）；
- SSE 错误契约（progress→error→meta 事件序完整，错误后 meta 终态不悬挂）；
- 启动期 dummy key WARN 的中文指引；NapCat 自检失败不阻断启动；PG 检索静默降级不影响启动与聊天；
- llm_call_log 观测：本次走查正是靠它完成根因定位（agent_name/model/error 三列直指问题）。

## 五、首次使用流程规则（新增功能复查清单）

后续新增功能/入口合入前，按此清单自查：

1. 新功能的**前置条件**按「用户授权/默认值/稍后补充」三分类标注在 README；可选项不得默认开启成首启告警源；
2. 新增 DB 种子/迁移后，必须在**全新空库**上走一遍首请求冒烟（参考本报告隔离实例方法），核对实际绑定的 provider 与文档 key 指引一致；
3. 所有失败路径的用户可见反馈必须含：**普通语言的原因 + 可执行的下一步**；HTTP 状态与业务状态不得矛盾（失败不用 200 包装——现有结构经 `status` 字段表达，需保证调用方能程序化识别）；
4. 可选依赖缺失时：功能面**不注册**（而非注册后调用失败），启动日志给关闭/安装出口；
5. 回退/降级行为（agent 回退、检索降级、工具降级）在响应或日志中可观察，不静默；
6. 每次走查结论追加到本报告「六、走查记录」。

## 六、走查记录

- 2026-09-11：首次全量走查（隔离实例 18080 + harness_it 库 + dummy key + 无 NapCat/Docker/PG）。实测项：启动日志、同步/流式首请求、参数校验、非法 agentId、knowledge sync；问题 1-6 如上；实测环境已清理（实例停止、测试库删除）。修复情况：全部待修复。
- 2026-09-11（下午）：首轮问题修复落地。决策：问题 1 采用**方案 B 仅改文档**（迁移改绑会波及真实库既有绑定，用户决策）；问题 6 一并修复。明细与验收：
  - **问题 2**：新增 `ModelAuthException`（镜像 `ModelQuotaException` 模式），AgentChatCaller 阻塞/流式两处识别 401 并转可执行指引（配哪个 key、重启、README 入口，厂商原文保留其后），GlobalExceptionHandler 映射 502。单测 `ModelAuthExceptionTest`（4 例）✓
  - **问题 3**：GlobalExceptionHandler 新增 `IllegalStateException` → 503 透出 message，知识库 sync 的「向量库未装配/写入失败」不再被兜底吞成「服务器内部错误」。单测 `GlobalExceptionHandlerTest`（6 例）✓
  - **问题 4**：`SandboxToolProvider.init()` 前置 Docker 真实 dial（复用 agentscope 同源 docker-java 客户端 `connect()` = docker info，无容器/镜像副作用），不可达降级空沙箱工具面 + WARN 出口提示，不再假就绪。单测 `SandboxToolProviderTest.dockerUnreachable` ✓
  - **问题 5**：NapCat 自检失败 WARN 尾部追加「设 napcat.enabled=false 关闭」出口提示；README 快速开始同款 NOTE ✓
  - **问题 6**：`ChatResponse` 新增 `agent` 字段透出实际使用的 agent 名（含显式 agentId 未命中回退场景），同步路径成功/失败响应均透出 ✓
  - **问题 1（B）**：README 快速开始新增「首次对话前核对 agent 绑定服务商」IMPORTANT 块 + 核对/改绑 SQL（agent_name / model_provider_id / provider）✓
  - 回归：全量 `mvn test` 435 例 0 失败 0 错误（1 例环境跳过）✓
  - 未验证项不变：真实 key 端到端对话、CLI 渲染细节、真实用户测试（本报告仍为 AI 走查 + 隔离实测，不代表用户认可）。
