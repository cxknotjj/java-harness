# 多 Agent 编排器设计：MultiAgentGraphAgent（路径 B）

> 日期：2026-08-26
> 范围：HARNESS_TODO ④（路径 B — 多 Agent 任务编排，Spring-AI Graph）。仅做「复杂路径」的编排执行器，并接入主 Agent 路由的 `COMPLEX` 分支。

## 背景与目标

* 现状：主 Agent 判断器（`LlmRouteJudge`）已能判断 `COMPLEX`，但复杂请求仍落回 `GeneralAssistantAgent` 单次调用——因为路径 B 编排器未实现。

* 目标：新增 **多 Agent 编排器** `MultiAgentGraphAgent`，用 `spring-ai-alibaba-graph-core` 的 `StateGraph` 编排「拆解 → 并行子任务 → 聚合」，作为复杂请求的执行体。

**已确认决策（用户）**

1. 子任务执行方式：**复用 ChatClient**（沿用 `GeneralAssistantAgent` 同款请求组装 + `ChatClientRegistry`）。
2. 子任务并行：**并行执行**（Lead 拆多个子任务后，各子任务节点并行，聚合节点汇总量）。
3. 异构：路径 B 由主 Agent 路由 `COMPLEX` 时切入 `MultiAgentGraphAgent`。
4. 不引入 Checkpointer（TODO ④ 的 Checkpointer 标记为可选——本次不做，不在范围）。

## 二、架构与数据流

```
主 Agent 前置判断：message -> COMPLEX
        │
        ▼
AgentService 按 anti agentName（"multi-agent"）路由到 MultiAgentGraphAgent
        │
        ▼
MultiAgentGraphAgent.execute(goal)  → 构建 StateGraph → CompiledGraph.invoke(Map)</x>
   state: { objective, subtasks[?string], results[?string×N], final?string }
        │
        ├─ [START] ──► lead 节点
        │     lead：调一次 LLM，把 objective 拆成 N 条子任务
        │           Map{ subtasks: [...], subtaskCount: N }
        │        └─addConditionalEdges(并行分发到 subTask-0..subTask-N-1)
        │
        ├─ subTask-i 节点（并行）
        │     读 subtasks[i]，调一次 ChatClient 生成该子任务结果
        │     写 Map{ results: append[i] = 结果 }
        │
        └─ aggregate 节点
              读 results[0..N-1]，调一次 LLM 汇总成最终回答
              Map{ final: ... }
        └─ END
```

**并行接线方式**（graph-core 1.1.2.2 的 `Command` 仅支持单目标，动态并行用固定子任务池实现）：

- 预注册**固定子任务池** `subtask-0..subtask-{MAX_SUBTASKS-1}`（默认 `MAX_SUBTASKS=4`）。
- `graph.addNode("lead", …)` → `graph.addEdge("lead", List.of("subtask-0",…,"subtask-3"))`：`addEdge(from, List)` 是**并行扇出**，lead 结束后同时派发到 4 个子任务节点。
- 每个 `subtask-k`：从 state 读 `subtask_k`，若 lead 实际拆解数 ≤ k 则无内容，节点快速返回空 Map（短短路）；否则调 ChatClient 生成 `result_k`。
- `graph.addEdge("subtask-k", "aggregate", …)` 全部收拢，`aggregate` 读 `result_0..result_{n-1}` 汇总。

**状态键约定**（`Map<String,Object>`，按 key 自动合并）：
- lead 写：`subtask_0..subtask_{n-1}`（n = 实际拆解数）+ `subtaskCount: n`
- subtask-k 写：`result_k: 文本`（无任务则写空）
- aggregate 写：`final: 最终回答`

## 三、组件改动

| 文件 | 动作 | 职责 |
|------|------|------|
| `agent/MultiAgentGraphAgent.java` | 新增 | `implements Agent`，构建并执行 StateGraph，复用 ChatClientRegistry |
| `config/agent/ChatAgentConfig.java` | 修改 | 注册 `multiAgent` bean |
| `service/impl/ChatServiceImpl.java` | 改为路由 | 主 Agent 判定 COMPLEX 时走 `MultiAgentGraph`，其余走 `General` |
| `service/RouteJudge.java` | 不变 | 已具备 |
| 测试 | 新增 | Graph 构造检查 + 节点 Mock 行为 |

## 四、MultiAgentGraphAgent 实现要点

**复用组装链路**（`ChatClient` 单次调用，与 `GeneralAssistantAgent` 保持一致）：
- 依赖：`ChatClientRegistry`、`AgentService`（取 model / 系统提示词）。
- `MultiAgentGraphAgent` 直接复用 `ChatClientRegistry.get(model)` 取到的 ChatClient，各自节点只发一次 `prompt().system(...).user(...).call().content()` 单次调用即可（子任务/汇总为独立短调用，不强制携带多轮记忆）。
- 不抽取共享封装类，避免改动现有 `GeneralAssistantAgent` 组装路径（低风险、YAGNI）。

**节点组织**：
- Lead 节点：调一次 LLM，提示词要求输出 `{"subtasks":["…","…"]}`；用 Jackson 解析为 `List<String>`；返回 `Map{ "subtasks": list, "subtaskCount": n, "subtask_0"…"subtask_{n-1}" }`。
- 子任务节点 k：从 state 读 `subtask_{k}`，调一次 LLM，返回 `Map{ "results_k": text }`。
- aggregate 节点：从 state 按 `results_0..results_{n-1}` 收集，拼接成 prompt，调一次 LLM，写 `Map{ "final": text }`。
- `execute(goal)`：构造 `OverAllState` from `{objective, sessionId}`，`compiledGraph.invoke(...)` 后 `state.value("final")` 返回。

**边界与错误**：任一节点 LLM 异常 → 该节点返回失败兜底 fallback 文本写最终结果，不让整个 Goal 失败（宁可降级，不阻塞）。若拆解/汇总失败，兜底把原 objective 作为最终回复返回。

**Checkpointer**：不引入（TODO 标记可选）。

## 四、对外契约（统一出口）

* `MultiAgentGraphAgent.execute(goal)` 返回最终回答 String；执行逻辑落回 `AgentService` 现有 `run()` 写 Goal 生命周期；`ChatService.writeBackContext` 统一写回 session_messages。与路径 A 完全一致，SSE/同步出口不变。

## 五、测试

- `agent/MultiAgentGraphAgentTest`：mock ChatClientRegistry + ChatClient → 校验 `execute` 返回最终回答、且确实走 Graph（mock ChatClient 固定返回）。因节点内部用真实 `CompiledGraph.invoke`，用固定返回让 lead/子任务/聚合可测。
- 回归：`LlmRouteJudgeTest`、`ChatServiceImplTest` 保持绿（RouteJudge 已 mock）。

## 六、范围（YAGNI）

- 不做垂直线 Graph 状态与 Checkpointer（可选 TODO）。
- 子任务个数按智能限定（lead 拆解上限，避免滚雪球，常量 `MAX_SUBTASKS` 默认 4）。
- 不新增路由外部配置（agent 表一行 `multi-agent` 走默认模型即可）。
- 不新增依赖（`spring-ai-alibaba-graph-core` 已在 pom）。