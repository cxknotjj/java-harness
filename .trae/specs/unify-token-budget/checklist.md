# Checklist

- [x] 新增 4 个 `app.context.*` 配置键全部经 `ContextBudgetProperties` 统一管理，代码零默认（int 缺省 0），数值唯一来源是 application.yaml（【2026-09-08 评审后两轮调整】最终口径：配置类零数字、全键统一 0 = 不限制；`ContextBudgetPropertiesTest.bindsApplicationYaml_productionValues` 直接绑定真实 yaml 锁生产值，漂移即报警）
- [x] `application.yaml` 生产默认值与 spec 配置表一致（2000 / 8000 / 4000 / 60000）（`ContextBudgetPropertiesTest.bindsYamlKebabCaseKeys_overridingValues` 证绑定线路 + 覆盖优先级）
- [x] lead / aggregator+路径A / 子任务专家 三类调用的 `maxTokens` 档位映射正确（单测断言捕获 ChatOptions）（`AgentChatCallerTest.call_maxTokensTierMapping_byRole`；路径 A 由 `GeneralAssistantAgent` final 档注入）
- [x] maxTokens 在生成侧设置（模型参数），未对已生成文本做事后裁剪（仅 `OpenAiChatOptions.maxTokens`，无输出文本截断逻辑）
- [x] 编排账本同步累计（BudgetLedger 按 roundtrip 增量记账，caller 发起前 + 每轮 usage 帧双时点熔断；真实 usage 优先、无 usage 按估算并带口径标记，流式与同步同口径）（`AgentChatCallerTest.call_realUsageFrame_recordsActualTokens` / `call_reportsEstimatedUsageToListener` / `stream_reportsRealUsageToLedger` / `stream_noUsage_estimatedFallback`；【2026-09-08 C1/I1 修复后口径】）
- [x] 模拟编排内超限：剩余子任务零 LLM 调用（mock 零交互断言），result 占位标注「预算超限跳过」（`MultiAgentGraphAgentTest.execute_overBudget_*`：`times(2)).stream()`）
- [x] 熔断后聚合正常执行且 prompt 含降级说明（N 个子任务未执行 + 已消耗/上限数字），编排正常终态非 FAILED（user captor 断言「预算降级说明」/「2 个子任务因预算超限未执行」，final = 正常聚合回答）
- [x] 真实部分跳过（lead + 前序子任务累计超限 → 后续子任务熔断跳过、前序结果保留进聚合，限并发=1 使跳过数确定；聚合照常产出最终回答）（【2026-09-08 I2 修复】`execute_partialBudgetExhaustion_skipsOnlyRemainingSubtasks`）
- [x] 子任务并行扇出限并发（`subtask-concurrency` yaml 键，ParallelNode 信号量错峰，0 = 不限；收窄熔断后在途超额窗口）
- [x] 未超限编排行为与现状一致（子任务全部执行）（`execute_withinBudget_allSubtasksRun_noDegradation`：4 次调用、无降级说明）
- [x] 各预算项数字估算 vs 真实口径可区分（降级说明注明「消耗含估算值」或「真实 usage 统计」；估算标记经 K_TOKEN_ESTIMATED 传递）
- [x] 会话维度无新增硬拦截路径（仅 llm_call_log 既有观测，未引入拒绝/短路逻辑）
- [x] `docs/guides/context-optimization.md` 补消费侧预算章节（第五节）；HARNESS_TODO.md Token 预算条目已勾选并附落地摘要
- [x] `mvn -s .mvn/settings.xml test` 全量测试通过（【2026-09-08 C1 修复后回归】320 tests, 0 failures, 0 errors, 1 skipped 为存量）
