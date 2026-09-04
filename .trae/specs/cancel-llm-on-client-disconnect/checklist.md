# Checklist

- [ ] `AgentChatCaller.call()` 基于 stream 通道实现，返回内容与原阻塞实现一致（正常完成回归用例通过）
- [ ] `call()`/`stream()` 支持可空 `BooleanSupplier cancelled`，旧签名向后兼容
- [ ] 取消置位时在途流式调用在 token 边界中止（takeUntil 生效，上游订阅被取消）
- [ ] 取消中止后：部分输出不按成功返回、不触发重试、llm_call_log 记录 client-cancelled
- [ ] cancelled 已置位时调用直接抛出，零 HTTP 请求
- [ ] 既有语义不回归：重试仅限首 token 前、ModelQuotaException 映射、未知名工具降级重试、工具注入与 ToolCallTracer 进度行
- [ ] token 观测口径：stream/call 统一为估算并标记 tokensEstimated=true
- [ ] MultiAgentGraphAgent 流式编排三节点（lead/subtask/aggregate）传入共享 cancelled 标志
- [ ] 同步路径（/api/chat）行为不变（cancelled=null 恒不短路）
- [ ] MultiAgentGraphAgentTest 新增断连中止用例且既有用例全绿
- [ ] 全量测试通过（mvn -s .mvn/settings.xml test）
- [ ] 路径 A 断连中止上游 HTTP 验证通过（或发现断链已立新任务）
- [ ] HARNESS_TODO.md L85 已勾选并存档实现要点
