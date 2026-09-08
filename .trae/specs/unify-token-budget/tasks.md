# Tasks

- [x] Task 1: 集中预算配置扩展——`ContextBudgetProperties` 新增 4 键 + 生产默认值
  - [x] 1.1 `ContextBudgetProperties` 新增字段与 Getter：`maxTokensLead` / `maxTokensFinal` / `maxTokensExpert` / `orchestrationBudget`（【2026-09-08 评审后两轮调整】最终口径：配置类零数字，数值唯一来源 application.yaml；全键统一 0 = 不限制）
  - [x] 1.2 `application.yaml` `app.context` 块补齐生产默认（lead 2000 / final 8000 / expert 4000 / orchestration 60000）并加中文注释
  - [x] 1.3 验证：配置绑定测试（绑定真实 yaml 锁生产值、kebab-case 键覆盖、零默认全 0）→ `ContextBudgetPropertiesTest`（bindsApplicationYaml_productionValues + bindsYamlKebabCaseKeys_overridingValues + zeroDefaults_allBudgetsOff）
- [x] Task 2: 输出封顶——`AgentChatCaller.buildSpec` 按角色档位设置 `maxTokens`
  - [x] 2.1 buildSpec 档位映射：lead → lead 档；aggregator 与路径 A 直出 → final 档；编排子任务专家 → expert 档（0 时不设置，保持现状）——路径 A 经 `GeneralAssistantAgent`（final 档字段注入）；model 为空时同样按角色生效
  - [x] 2.2 测试：`AgentChatCallerTest` 新增档位断言（捕获 ChatOptions.maxTokens；0 时不写入）
- [x] Task 3: 编排消费上限熔断——账本 + 子任务短路 + 聚合降级
  - [x] 3.1 编排级内存账本（AtomicLong + 估算标记，input 注入 + Replace 策略注册进 state），`AgentChatCaller` 调用完成后经 `UsageListener` 同步回调累计（真实 usage 优先，口径同 tokens_estimated）
  - [x] 3.2 子任务节点执行前熔断检查（仿 isCancelled 模式）：超限跳过、零 LLM 调用、result 写「预算超限跳过」占位
  - [x] 3.3 聚合节点不熔断：prompt 注入「N 个子任务因预算超限未执行」+ 已消耗/上限数字（注明估算口径）；占位 result 不计入真实结果
  - [x] 3.4 测试：`MultiAgentGraphAgentTest` 新增熔断用例（超限 → 剩余子任务 mock 零交互、final 含降级说明；未超限 → 行为不变）——同步 + 流式双路径
- [x] Task 4: 收尾——文档回写与全量回归
  - [x] 4.1 `docs/guides/context-optimization.md` 补「消费侧预算」章节（配置表 + 熔断行为 + 口径说明）
  - [x] 4.2 HARNESS_TODO.md L75-78 勾选并附落地摘要（含「会话累计不设硬配额」的评估结论）
  - [x] 4.3 全量回归：`mvn -s .mvn/settings.xml test` 全绿（310 用例 0 失败 0 错误）
- [x] Task 5: C1/I1/I2 评审修复（2026-09-08，方向 b：熔断下沉 caller roundtrip 粒度）
  - [x] 5.1 caller 层：`BudgetLedger` 接口（`overBudget()`/`recordUsage`）+ `BudgetExceededException`；call/stream 双路径在「发起前 + 每轮 roundtrip usage 帧增量记账并复检」两时点门控，工具循环每轮受检（3.1/3.2 的批前检查由此废弃，UsageListener 被 BudgetLedger 取代）；顺带修 I1（stream 与 call 同口径，无 usage 估算兜底）
  - [x] 5.2 编排层：节点句柄改造（lead/子任务门控、聚合 record-only）；子任务并行扇出限并发 `subtask-concurrency`（yaml 新键，ParallelNode 信号量）
  - [x] 5.3 测试：caller 门控/记账 4 用例 + 真实部分跳过用例（I2，`execute_partialBudgetExhaustion_skipsOnlyRemainingSubtasks`）+ 配置绑定用例补 subtask-concurrency；全量回归 320 用例全绿
  - [x] 5.4 文档：spec 设计决策回写（Task 3 拓扑矛盾与方向 b 依据）+ 真实部分跳过 Scenario；context-optimization.md 5.2/5.4 重写；审查报告 C1/I1/I2 标记已修复；checklist 同步

# Task Dependencies

- Task 2 与 Task 3 相互独立，可并行；均依赖 Task 1（配置键先行）
- Task 3 内部 3.1 → 3.2/3.3 顺序（短路判定依赖账本）
- Task 4 依赖 Task 2/3 完成
