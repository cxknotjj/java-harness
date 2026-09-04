# Prompt 动态装配（核心 3 项）Spec

> 对应 HARNESS_TODO.md「prompt 的动态加载」子项 4（agent 角色 prompt 的组装）、5（记忆上下文的动态注入）、6（工具 Schema 的延迟加载）。
> 子项 1/2/3（skill/tool/mcp 装配数据化）不在本次范围；本 spec 预留扩展点，后续接入。

## Why

当前两路径的 system prompt 是散落在代码各处的单块字符串拼接（角色 persona、工具纪律、输出约定硬编码在 `GeneralAssistantAgent`/`AgentChatCaller.predictSubtask` 等），无统一组装概念；编排路径（lead/子任务/聚合）完全没有会话记忆注入，模型脱离对话上下文；工具 schema 每请求全量注入（含 MCP 大 schema），token 开销固定且随工具面增长线性上涨。

## What Changes

- **统一 Prompt 组装管线（子项 4）**：新增 `PromptAssembler`——每请求按 agent 名把 system prompt 按段组装：角色段（agent 表 prompt/内置兜底，现状查表逻辑平移）+ 工具纪律段（现硬编码的工具使用纪律收敛为段）+ 输出约定段 + **skill 段扩展点（预留接口，不实现加载）**。`GeneralAssistantAgent` 与 `AgentChatCaller` 两路径统一接入，消除散落拼接。
- **记忆上下文动态注入（子项 5）**：新增记忆注入策略——按角色决定是否注入：`general`（路径 A）保持现状；编排 `lead` 注入会话记忆（与简单节点相同的装配方式与预算裁剪）；`aggregator` 与子任务专家不注入（聚合忠实于各子任务结果，子任务上下文由 lead 在子任务描述中传递）。
- **工具 Schema 延迟加载（子项 6）**：新增会话级两段式工具暴露——
  - 首轮请求只注入「轻量态」工具 callback（工具名 + 一句话描述，参数 schema 置空）+ 由 `PromptAssembler` 在 system 注入工具索引段（名称/用途/何时用）；
  - 模型需要某工具时调用内置元工具 `expand_tool(tool_name)`：服务端把该工具加入**会话级已展开集合**，并向模型返回该工具的完整说明（含参数说明）；
  - 已展开工具在其后请求中按完整 schema 注入；未展开工具被直接调用时执行返回引导文本（提示先 expand），不报错、不执行真实逻辑；
  - 提供配置开关 `app.prompt.lazy-tools.enabled`（默认 true），关闭后回退现状全量注入。
- **BREAKING**：开启延迟加载后，工具调用语义变为两段式（未展开的工具不再直接执行）；会话级展开集存内存，应用重启后需重新 expand（可接受，expand 零成本）。

## Impact

- 受影响 specs：无既有 spec 依赖；后续「skill/tool/mcp 动态装配」spec 将复用 `PromptAssembler` 的段落机制与 skill 段扩展点。
- 受影响代码：
  - 新增 `prompt` 包：`PromptAssembler`、`PromptSection`（段抽象）、`MemoryPolicy`、`ToolLazyManager`（轻量/完整 callback 切换 + 会话展开集 + expand_tool 元工具）
  - 改造：`GeneralAssistantAgent`（接入 Assembler 与 LazyManager）、`AgentChatCaller.buildSpec`（接入 Assembler、记忆策略、LazyManager）、`ToolAssignments`（补充工具用途元数据供索引段生成）
  - 配置：`application.yaml` 新增 `app.prompt.lazy-tools.enabled` 开关（记忆注入复用现状装配，无新增配置）
  - 测试：`PromptAssemblerTest`、`MemoryPolicyTest`、`ToolLazyManagerTest` + 既有两路径测试适配

## ADDED Requirements

### Requirement: Prompt 组装管线
系统 SHALL 提供 `PromptAssembler`，对任意 agent 名在请求期组装 system prompt，段落按固定次序拼接：角色段 → 记忆策略说明段（可选）→ 工具索引段（延迟加载开启时）→ 工具纪律段 → 输出约定段 → skill 段（预留扩展点，当前恒为空）。角色段来源优先级保持现状：agent 表 prompt > 角色内置兜底 > 默认 system prompt。

#### Scenario: 编排子任务 prompt 组装
- **WHEN** lead 指派子任务给 researcher 并发起调用
- **THEN** system prompt 由 Assembler 产出：agent 表 researcher 行 prompt 作为角色段 + 工具纪律段 + 输出约定段，`predictSubtask` 中原有的 persona/纪律字符串拼接代码被删除

#### Scenario: skill 扩展点预留
- **WHEN** 任何 agent 的 prompt 被组装
- **THEN** skill 段位存在且当前输出空串；后续 skill 装配（Markdown 目录方案）只需实现段的内容提供者，不改组装管线

### Requirement: 记忆上下文动态注入
系统 SHALL 按角色策略注入会话记忆：`general`（路径 A）保持现状；编排路径中 `lead` 注入会话记忆，装配方式与简单节点一致（`MessageChatMemoryAdvisor` + 既有 `ContextAssemblingAdvisor` 预算裁剪）；`aggregator` 与子任务专家不注入记忆。

#### Scenario: lead 拆解携带对话上下文
- **WHEN** 用户在会话中说「结合我们刚才聊的主题出一份报告」触发 COMPLEX 编排
- **THEN** lead 节点的请求包含会话记忆（与路径 A 同口径），能理解「刚才聊的主题」指代

#### Scenario: 非 lead 编排节点不带记忆
- **WHEN** 编排并行执行 4 个子任务节点并进入聚合
- **THEN** 子任务与聚合节点请求均不含会话记忆（子任务上下文由子任务描述承载，聚合忠实于各子任务结果），token 消耗不随记忆增长

### Requirement: 工具 Schema 延迟加载
系统 SHALL 在 `app.prompt.lazy-tools.enabled=true` 时启用两段式工具暴露：请求级工具面中未展开工具以轻量态注入（名称 + 一句话描述，参数 schema 置空），system prompt 附工具索引段；模型调用 `expand_tool(tool_name)` 后该工具加入会话级已展开集合并返回完整说明，其后的请求按完整 schema 注入。未展开工具被直接调用时返回引导文本（不执行真实逻辑）。开关关闭时回退全量注入现状。

#### Scenario: 首轮轻量注入
- **WHEN** 新会话首轮请求带工具（如 researcher 的 fetchUrl + MCP 工具）
- **THEN** 请求中工具参数 schema 为空（仅名称与描述），system 含工具索引段，工具 schema token 开销显著低于全量注入

#### Scenario: expand 后正式调用
- **WHEN** 模型调用 `expand_tool("fetchUrl")` 后于同一会话下一轮调用 `fetchUrl`
- **THEN** expand 的工具结果包含 fetchUrl 完整参数说明；后续请求中 fetchUrl 以完整 schema 注入并正常执行

#### Scenario: 未展开直接调用的自愈
- **WHEN** 模型未 expand 直接调用某工具
- **THEN** 工具结果返回引导文本（提示先调用 expand_tool 获取参数说明），模型下一轮 expand 后正常使用，不抛「No ToolCallback found」类错误

#### Scenario: 开关回退
- **WHEN** `app.prompt.lazy-tools.enabled=false`
- **THEN** 所有工具按完整 schema 全量注入，行为与现状一致（expand_tool 不注册）
