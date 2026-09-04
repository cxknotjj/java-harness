# Agent 表驱动自动注册 Spec

## Why

当前对话 Agent 由 `ChatAgentConfig` 手工注册为 Spring bean（仅 general/deepseek/multi-agent 三个），`AgentServiceImpl` 路由表按 bean 列表构建——agent 表新增一行（如 nailong）后 `/agent` 切换报 400「未知 Agent」。「新增 Agent 必须注册 bean + 改代码发版」与「配置进数据库、改库即生效」的既有模式（agent 行配置、provider 注册表热刷新）相悖。

## What Changes

- **新增 `AgentRegistry`**（agent 包）：表驱动的 Agent 路由注册表——
  - **查表仅依赖 Dao 层**（`AgentConfigProvider`，含 `is_internal` 读取），不调用 `AgentService` 方法；`AgentService` 仅作为 `GeneralAssistantAgent` 构造参数经 `ObjectProvider`/`@Lazy` 透传（GA 内部查配置的现实依赖），注册表自身与其零方法调用，无循环依赖
  - 启动时读 agent 表，把全部 `is_internal=0` 的行自动注册为对话 Agent（`GeneralAssistantAgent` 实例，prompt/model/工具按行配置生效）
  - 运行时惰性热注册：路由未命中 → 查 agent 表 → 命中即构造注册并执行；注册原子性由 `ConcurrentHashMap.computeIfAbsent` 保证（并发同名请求仅构造一次，防重复初始化）
  - **Fail-safe 数据容错**：启动逐行注册 try-catch，单行脏数据（agentName 空白/构造异常）warn 跳过不阻断启动；惰性注册查表或构造失败时按「未命中」处理并 warn，不向请求方抛底层异常
  - **内部角色由数据驱动**：新增 `is_internal` 列（V9 迁移，`TINYINT NOT NULL DEFAULT 0`，存量 `multi-agent`/`lead`/`aggregator` 行置 1），排除逻辑完全以该字段为准，代码不保留硬编码角色清单（新增内部角色 = 置 1，免改代码）
  - **兜底优先级单一事实来源**：`general` 行存在 → 完全按 DB 行注册（DB 配置优先，代码不掺合）；仅当 DB 无 `general` 行时注册代码兜底实例（内置默认 prompt + 默认模型），启动日志 warn 提示补配置——两来源不合并、不漂移
- **新增 `AgentConfigProvider.listAgentNames()`**：列全表行名（异常返回空列表，与现有容错风格一致）
- **新增 Flyway V9 迁移**：agent 表加 `is_internal` 列 + 存量内部角色行 UPDATE 置 1；`AgentEntity` 增加对应字段
- **改造 `AgentServiceImpl`**：路由表从「Spring bean 列表」改为 `AgentRegistry`（multi-agent 编排 bean 预注入 + 表驱动动态注册）；`agentNames()` 返回动态路由表
- **改造 `ChatAgentConfig`**：删除 `generalAgent`/`deepseekAgent` 手工 bean，`AgentRegistry` bean 承载其职责；`multiAgent`/`toolLazyManager`/`graphCheckpointSaver` bean 不动。**BREAKING**：仅存在于代码、不在 agent 表中的 agent 名（当前无此情况）将不可用；注册入口从「bean + 表行」收敛为「表行」
- **存档口径更新**：HARNESS_TODO「新增 Agent = 注册一个 bean + agent 表一行」收敛为「新增 Agent = agent 表一行（内部角色行置 is_internal=1）」

## Impact

- 受影响 specs：无；与 `assemble-prompt-dynamically` 的 PromptAssembler 无耦合（Assembler 按 agent 名查表，名字来源不变）
- 受影响代码：
  - 新增：`AgentRegistry`（含 computeIfAbsent 惰性注册）、Flyway `V9__agent_internal_flag.sql`
  - 改造：`AgentServiceImpl`（路由委托）、`ChatAgentConfig`（删两个 bean + 注册 AgentRegistry）、`AgentConfigProvider`（+listAgentNames +is_internal 读取）、`AgentEntity`（+字段）
  - 测试：新增 `AgentRegistryTest`（注册/排除/fail-safe/惰性并发/兜底优先级）；适配 `AgentServiceImplTest`；`ChatServiceImplTest` 等既有用例不回归
- 依赖注入链：`AgentRegistry` 不依赖 `AgentService` 的任何方法调用（仅透传构造参数），与 `AgentServiceImpl`（依赖 Registry）无循环

## ADDED Requirements

### Requirement: agent 表驱动自动注册
系统 SHALL 在启动时读取 agent 表，将全部 `is_internal=0` 的行自动注册为可路由的对话 Agent（`GeneralAssistantAgent` 实例，name=行 agentName）；对话请求路由以该注册表为准；排除逻辑完全由 `is_internal` 字段驱动，代码不硬编码角色清单。

#### Scenario: 表行即对话 Agent
- **WHEN** agent 表存在 `nailong` 行（is_internal=0）并执行 `/agent` 切换到该行
- **THEN** 请求正常执行，system prompt/模型/工具分配按该行配置生效，不再报「未知 Agent」

#### Scenario: 内部角色不注册
- **WHEN** agent 表 `lead`/`aggregator` 行 is_internal=1
- **THEN** 它们不出现在 `agentNames()` 与 `/agent` 可切换列表；`multi-agent`（is_internal=1）仍路由到 `MultiAgentGraphAgent` bean

#### Scenario: 脏数据 fail-safe
- **WHEN** agent 表某行 agentName 为空或实例构造抛异常
- **THEN** 该行 warn 跳过、其余行照常注册、应用正常启动，单个脏行不拖垮注册流程

### Requirement: 运行时惰性热注册
系统 SHALL 在路由未命中时查询 agent 表：命中 `is_internal=0` 的行则经 `ConcurrentHashMap.computeIfAbsent` 原子构造注册并继续执行（并发同名请求仅构造一次）；查表异常/行不存在/构造失败均按「未命中」处理（warn 后抛「未知 Agent」含可用列表），不向请求方泄漏底层异常。运行中新增的表行无需重启即可用。

#### Scenario: 运行中插行即时生效
- **WHEN** 应用运行期间向 agent 表插入新行 `foo`，随后 `/agent` 切换到 `foo`
- **THEN** 首次使用即完成注册并正常执行，无需重启应用

#### Scenario: 并发首次调用不重复构造
- **WHEN** 多个请求并发首次路由到同一未注册行
- **THEN** 该 Agent 实例仅构造一次（computeIfAbsent 原子性），全部请求拿到同一实例

### Requirement: 默认兜底不回退
系统 SHALL 保证 `general`（DEFAULT_AGENT）始终可路由，且配置来源单一：agent 表存在 `general` 行时完全按 DB 配置注册（单一事实来源）；仅当 DB 无该行时注册代码兜底实例（内置默认 system prompt、默认模型）并启动日志 warn 提示补配置——DB 配置与代码兜底不合并。

#### Scenario: general 行缺失兜底
- **WHEN** agent 表被清空或无 `general` 行
- **THEN** 系统启动成功，`general` 仍可用（代码兜底配置），路由兜底逻辑（未命中 agentId 回退 general）不失效，日志含「agent 表缺 general 行」提示
