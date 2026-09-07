# 主 Agent 前置判断（路由判断器）设计：LlmRouteJudge

> 日期：2026-08-26
> 范围：HARNESS_TODO ②（主 Agent 前置判断 / 路由判断器）。仅实现「分流判断」这一薄层，不执行具体任务。

## 背景与目标

* 现状：`ChatServiceImpl.chat()` / `streamReactive()` 全部直接走 `general` Agent 单次调用（路径 A），没有前置分流。多 Agent Graph（路径 B）尚未实现。

* 目标：新增一个 **主 Agent 路由判断器**——只判断当前请求是「简单(场景A)」还是「复杂(场景B)」并输出决策，不执行具体任务，保持入口薄。为后续接入路径 B（Spring-AI Graph）预留清晰的分流位点。

**已确认决策（用户）**

1. 判断方式：**LLM 判断**——用一次轻量 LLM 调用让模型输出结构化 JSON 决策。
2. 路径 B 落地：当前「复杂路径 B（多 Agent Graph）」尚未实现，因此本次 **complex 分支暂时仍落到现有 general 单次调用**，但代码结构预留 switch 分流位点。
3. 结果可见性：route 决策仅以**日志**体现 + **单测**断言，不改对外 API 契约（`ChatResponse` / SSE meta 不变）。

## 二、架构与数据流

```
用户 message
  → RouteJudge.judge(message)   （主 Agent 前置判断，薄环）
       → LlmRouteJudge：一次 LLM 调用 → 结构化 JSON { "route": "simple" | "complex" }
       → 解析失败/超时/异常 → 兜底 SIMPLE
  → 拿到 RouteDecision（SIMPLE / COMPLEX）
      → 打日志 [route] message → SIMPLE/COMPLEX
      → 分流位点（当前两分支都走现有单次调用，预留复杂路径）
          SIMPLE  → general Agent 单次调用   （路径 A）
          COMPLEX → （Graph 未实现，仍 general；接入时换多 Agent 编排）
```

## 三、组件改动

| 层 | 文件 | 动作 | 职责 |
|----|------|------|------|
| 枚举 | `domain/RouteDecision.java` | 新增 | `SIMPLE` / `COMPLEX`，附带 LLM 原始返回的归一化解析（含兜底） |
| 接口 | `service/RouteJudge.java` | 新增 | `RouteDecision judge(String message)` |
| 实现 | `service/impl/LlmRouteJudge.java` | 新增 | 用 ChatClient 调 LLM 分类为 JSON，异常/解析失败兜底 SIMPLE |
| 编排 | `service/impl/ChatServiceImpl.java` | 修改 | `chat()` / `streamReactive()` 前置 `judge()` + 日志 + 分流位点 |
| 配置 | —— | 不变 | 对话框（可选路由接线，暂不做） |
| 测试 | `LlmRouteJudgeTest` + `ChatServiceImplTest` | 新增/修改 | 判定正确性 + 兜底 + 注入 agent |

## 四、LlmRouteJudge 设计细节

**依赖注入：** `ChatClientRegistry`（按 model 取客户端）+ `ChatClient.Builder` 无关。沿用 GeneralAssistantAgent 的客户端获取方式：`clientRegistry.get(model)`。

**提示词：** 系统提示词要求模型严格只输出一行 JSON：

```
你是 Harness 的主路由判断器。判断用户请求属于「简单」还是「复杂」。
只输出 JSON，不要任何解释：{"route":"simple"} 或 {"route":"complex"}。
- 简单(simple)：无需工具、无需拆分子任务，单次回答即可（如问候、闲聊、简短问答、讲笑话）。
- 复杂(complex)：需联网搜索、编码执行、多步骤处理、需拆分多个子任务（如调研竞品并输出报告）。
```

**判定解析：** 用 `ObjectMapper` 解析返回文本，取 `route` 字段：
- `complex` → `COMPLEX`
- 其它/缺失/解析失败 → `SIMPLE`（兜底，TODO ⑤「宁可简单」）

**异常兜底：** LLM 调用抛异常 / 超时 / 返回非 JSON → 一律返回 `SIMPLE`，不向调用方抛错（保证请求不被阻塞）。

**同步/响应式：** 判断是「前置旁路」，只同步调用一次（复用处在一个 `chat()` 与 `streamReactive()` 都能同步短调用、然后进入各自执行体的位置）。为不干扰流式，`streamReactive` 的判断在进入 flux 前同步完成一次；若后续需要 true 非阻塞可改为 `Mono`，本次保持简单。

**不改变对外契约：** route 决策**只写日志**（`log.info("[route] message->SIMPLE ...")`），不改 `ChatResponse`/SSE 格式。

## 五、数据管理 / 并发边

* 判断调用是单独的一次 LLM 调用，与执行各自独立，不共享 Goal。
* 同步调用判断阻塞；在 `streamReactive` 中判断也位于 flux 组装前（前缀阶段），避免进入 Reactor 回调线程后才阻塞。低配判断模型。

## 六、错误处理

* 判断阶段任何错误 → 兜底 SIMPLE（记 WARN 日志不抛出）。
* 不分发到路径 B 时不受影响。

## 七、测试

* `LlmRouteJudgeTest`：
  - mock `ChatClientRegistry` 返回 mock `ChatClient`（`prompt().system().user().call().content()` 依次 stub）。
  - 合法 JSON `{"route":"complex"}` → 断言 `COMPLEX`；`{"route":"simple"}` → `SIMPLE`。
  - 非法 JSON / 空返回 → `SIMPLE`（兜底）。
  - mock `call` 抛异常 → `SIMPLE`（兜底）。
* `ChatServiceImplTest`：注入 mock `RouteJudge`，断言 `chat()`/`stream()` 调用后调用 `judge` 且分流转入 agent。

## 八、范围（YAGNI）

- 不做 URL / 不拆 Graph：路径 B 实现是本次范围外的另一 TODO（④），本次仅预留分流位点。
- 不改变 `ChatResponse`/SSE 对外格式。
- 不新增依赖（复用 spring-ai ChatClient / Jackson）。