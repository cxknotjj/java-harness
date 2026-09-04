# Tasks

- [x] Task 1: Prompt 组装管线 PromptAssembler（子项 4，先行为后续两项提供挂载点）
  - [x] 1.1 新增 `prompt` 包：`PromptSection` 段抽象（name/order/render）+ `PromptAssembler`（按 agent 名查 `AgentConfigProvider` 组装：角色段→工具索引段→工具纪律段→输出约定段→skill 段扩展点[当前空实现]）
  - [x] 1.2 `ToolAssignments` 补充工具用途元数据（工具名→一句话用途），供工具索引段与轻量态描述复用
  - [x] 1.3 `GeneralAssistantAgent` 接入 Assembler（替换现状直接 `config.prompt()` 的 system 来源，保持 agent 表优先级不变）
  - [x] 1.4 `AgentChatCaller.predictSubtask` 等处的硬编码 persona/工具纪律拼接迁移到 Assembler 段，删除散落字符串
  - [x] 1.5 单测 `PromptAssemblerTest`：段落次序、角色段优先级（表 prompt/兜底/默认）、skill 段扩展点可插拔
- [x] Task 2: 记忆上下文动态注入 MemoryPolicy（子项 5，与 Task 3 可并行）
  - [x] 2.1 新增 `MemoryPolicy`：按角色名决定注入策略——`general` 保持现状；`lead` 注入会话记忆（与 general 同一装配方式与预算裁剪）；`aggregator` 与子任务专家返回「不注入」
  - [x] 2.2 `AgentChatCaller.buildSpec` 按策略挂载 `MessageChatMemoryAdvisor`（复用 `SessionService`/memoryStore 与 `ContextAssemblingAdvisor` 预算裁剪，无会话 ID 时跳过）
  - [x] 2.3 单测 `MemoryPolicyTest` + 编排节点注入断言（lead 带、aggregator/子任务不带、无会话 ID 不挂 advisor）
- [x] Task 3: 工具 Schema 延迟加载 ToolLazyManager（子项 6，依赖 Task 1 的工具索引段）
  - [x] 3.1 新增 `ToolLazyManager`：轻量态 callback 包装（名称/描述保留、inputSchema 置空、call 返回引导文本）+ 会话级已展开集合（`ConcurrentHashMap<sessionId, Set<String>>`）+ 内置 `expand_tool` 元工具（展开并返回完整参数说明）
  - [x] 3.2 配置开关 `app.prompt.lazy-tools.enabled`（默认 true）：开启时两路径请求级工具面经 LazyManager 包装 + Assembler 注入工具索引段；关闭时全量注入现状、expand_tool 不注册
  - [x] 3.3 `GeneralAssistantAgent`/`AgentChatCaller` 接入；适配既有 `isUnknownToolCall` 降级逻辑（轻量名已注册，原「No ToolCallback found」场景收敛）
  - [x] 3.4 单测 `ToolLazyManagerTest`：轻量态 schema 置空、未展开调用返回引导文本不执行、expand 后完整注入、跨会话展开集隔离、开关回退
- [x] Task 4: 全量回归与收尾（依赖 Task 1/2/3 全部完成）
  - [x] 4.1 全量 `mvn -s .mvn/settings.xml test` 通过（既有两路径/编排/SSE 用例不回归）
  - [x] 4.2 HARNESS_TODO.md L78（子项 4）、L80（子项 5）、L81（子项 6）勾选，存档区补记实现要点（注明 skill 段扩展点与子项 1/2/3 的衔接方式）

# Task Dependencies

- Task 3 依赖 Task 1（工具索引段经 Assembler 注入、工具用途元数据复用）
- Task 2 与 Task 1/3 无强依赖，可并行
- Task 4 依赖 Task 1/2/3 全部完成
