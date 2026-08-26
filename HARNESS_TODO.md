# Harness 执行外壳 · 两路径架构 TODO

> 目标架构：把 **Harness** 作为请求的执行外壳，统一从应用层接入；主 Agent 前置判断后，按任务复杂度分流到两条路径，**不是所有请求都强制走复杂多 Agent 流程**。
>
> 现状与目标的差异：下方「✅ 现状」「⬜ 待实现」明确区分哪些已存在于项目、哪些是待办。

---

## 一、目标数据流

```
1. 请求进入 Harness
     请求（message / sessionId / agentId）→ Controller（Harness 外壳入口）
        │
2. 加载会话原始数据 + 执行上下文组装
     sessionId → 读取 session_messages → 还原 Message 列表
     → 过滤 / 截断 / 角色格式化 → 组装成可供 LLM 消费的执行上下文
        │
3. 主 Agent 前置判断（决定走哪条路径）
     ├─ 场景 A：问题简单（无需工具、无需拆分子任务）
     │      → 普通单次大模型调用芯片，直接生成回答 → 结束
     └─ 场景 B：问题复杂（需搜索/代码/多步骤处理）
            → 进入 多 Agent 编排链路（Spring-AI Graph）
                  Lead Agent 拆解 → 子任务 → 子 Agent 并行/串行 → 聚合结果 → 结束
```

---

## 二、TODO 清单（按落地顺序）

### ① Harness 入口与上下文组装（现状：部分已具备）

- [x] **请求接入 Harness 入口**：`ChatController`（`/api/chat` 同步、`/api/chat/stream` 响应式）已是统一入口，ChatService/AgentService 承担外壳编排。
- [x] **加载会话原始数据（session_message）**：`SessionService.loadContext(sessionId)` 已读取 `session_messages` 的 JSON 快照并还原为 `List<Message>`。
- [x] **执行上下文组装（过滤/截断/角色格式化）**：已由 `ContextAssemblingAdvisor`（Spring AI Advisor 拦截器）实现，挂在 `GeneralAssistantAgent` 的 ChatClient 链上：
  - 按 token 预算裁剪（近似估算，保留 system + 最近 N 轮，从旧丢弃）
  - 过滤空/系统噪声消息
  - 保证 role 顺序（system → user/assistant 交替，压制连续同类）
  - 单测覆盖：见 `ContextAssemblingAdvisorTest`

### ② 主 Agent 前置判断（现状：已具备）✅

- [x] **主 Agent / 路由判断器**：`RouteJudge` 接口 + `LlmRouteJudge` 实现，通过 LLM 判断请求属于「简单(场景A)」还是「复杂(场景B)」。
  - 输入：用户 message
  - 输出：`SIMPLE` / `COMPLEX` 结构化决策（`RouteDecision`）
  - 已接入 `ChatServiceImpl.chat()` / `streamReactive()` 前置分流（日志输出，不改对外契约）
- [x] 主 Agent 判断仅做「分流」，不执行具体任务（入口薄）。
- [x] 判断失败/超时/非 JSON 兜底 `SIMPLE`（宁可简单，TODO ⑤） | 单测覆盖：`LlmRouteJudgeTest`

### ③ 路径 A —— 普通单次调用芯片（现状：已具备）✅

- [x] `GeneralAssistantAgent` 直接单次调用大模型（同步 `call()` / 响应式 `stream()`），携带 session 历史。
- [x] 会话记忆写回（`ChatService.writeBackContext` → `session_messages`）。
- [ ] （可选）将路径 A 的调用从「ChatService 直接调 Agent」抽出，经主 Agent 路由原子化：`A 调用 → GeneralAssistantAgent → 单次 LLM → 返回`。

### ④ 路径 B —— 多 Agent 任务编排（Spring-AI Graph）（现状：依赖预留，未实现）⬜

- [ ] 引入 `spring-ai-alibaba-graph-core`（依赖已在 pom 中预留）。
- [ ] 新增 **多 Agent 编排器**：基于 StateGraph 编排
  - `Lead Agent 节点`：接收复杂目标 → 拆分为子任务
  - `子任务节点`：并行/串行执行（子 Agent：搜索、代码、多步骤处理）
  - `聚合节点`：收集各子 Agent 结果 → 生成最终回答
- [ ] 复杂路径的执行结果同样走统一的「会话记忆写回 + SSE/同步响应」出口，保证两条路径对外契约一致。
- [ ] 为复杂路径注册 Checkpointer（可选，落库断点）——当前已从项目移除，如需再评估是否引入。

### ⑤ 兼容兜底与统一出口（现状：部分已具备）✅/⬜

- [x] 未命中 agentId / 判断失败时回退默认 `general`（简单路径）——已具备兜底。
- [x] 两条路径共享统一响应出口（`ChatController` 同步/SSE）。
- [ ] 主 Agent 判断异常时**兜底走简单路径**（宁可简单，不强行走复杂流程）——需在路由实现时保证。

---

## 三、组件映射（现有项目 → 目标架构）

| 目标架构角色 | 现有实现 / 预留 |
|---|---|
| Harness 外壳入口 | `ChatController` / `ChatService` / `AgentService` |
| 会话原始数据加载 | `SessionService.loadContext(sessionId)` |
| 上下文组装(过滤/截断) | `ContextAssemblingAdvisor` |
| 主 Agent 前置判断 | `RouteJudge` / `LlmRouteJudge` |
| 路径 A(简单) 单次调用 | `GeneralAssistantAgent` |
| 路径 B(复杂) 多 Agent Graph | **无（依赖 `spring-ai-alibaba-graph-core` 已预留）** |
| 统一响应出口 | `ChatController` 同步 + 响应式 SSE |
| 兼容兜底 | `AgentService` 回退 general |

---

## 四、验收标准

- [ ] 一个简单请求（如「你好」「讲个笑话」）→ 走路径 A，单次调用返回，无多余 Graph 开销。
- [ ] 一个复杂请求（如「写一个项目并调研竞品，输出报告」）→ 主 Agent 判定为复杂，走路径 B 多 Agent Graph。
- [ ] 主 Agent 判断失败/超时 → 兜底走路径 A，不阻塞请求。
- [ ] 两条路径均保留多轮会话记忆与 SSE 流式输出，对外响应格式一致。