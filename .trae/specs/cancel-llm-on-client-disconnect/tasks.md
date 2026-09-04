# Tasks

- [x] Task 1: AgentChatCaller 取消能力改造（核心，先行）
  - [x] 1.1 `call()`/`stream()` 增加可空 `BooleanSupplier cancelled` 参数的重载（旧签名委托 null 保持兼容）
  - [x] 1.2 阻塞 `call()` 改为 stream 通道背书：收集全部 token 返回、token 观测走估算口径（`tokensEstimated=true`）
  - [x] 1.3 `stream()` 链上加 `.takeUntil(cancelled)`：取消在 token 边界中止上游订阅；取消后抛取消异常（部分输出不按成功返回、不重试、记录 `error_msg=client-cancelled`）
  - [x] 1.4 保留既有语义：重试仅限首 token 前、配额硬错误映射、未知名工具降级重试、工具注入/追踪/advisor 挂载
  - [x] 1.5 更新/新增单测：正常完成回归（AgentChatCallerTest / AgentChatCallerRetryTest 全绿）+ 新增取消场景（执行中置位 → 中止且无重试；置位后调用 → 直接抛出零请求）
- [x] Task 2: MultiAgentGraphAgent 传递取消令牌
  - [x] 2.1 流式编排路径：lead/subtask/aggregate 三处 caller 调用传入共享 `cancelled` 标志（`cancelled::get`）
  - [x] 2.2 同步路径维持 null（行为不变），注释说明差异
  - [x] 2.3 扩展 MultiAgentGraphAgentTest：断连时在途调用中止、零新增调用、goal 落库 FAILED（既有用例不回归）
- [x] Task 3: 端到端验证与收尾
  - [ ] 3.1 路径 A 验证：SSE 客户端断开 → Reactor cancel 传播中止上游 HTTP（现有响应式链，不改代码；发现断链则立新任务）——留待运行时人工验证
  - [x] 3.2 全量 `mvn -s .mvn/settings.xml test` 通过（202 用例，1 skip 为既有 MCP 用例）
  - [x] 3.3 HARNESS_TODO.md L85 勾选并在存档区补记实现要点（含 call() 估算口径的取舍说明）

# Task Dependencies
- Task 2 依赖 Task 1（取消令牌先落地）
- Task 3 依赖 Task 1、Task 2
