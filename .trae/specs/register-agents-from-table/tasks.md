# Tasks

- [x] Task 1: 数据层：is_internal 列与列全表能力
  - [x] 1.1 Flyway `V9__agent_internal_flag.sql`：agent 表加 `is_internal TINYINT NOT NULL DEFAULT 0`，存量 `multi-agent`/`lead`/`aggregator` 行 UPDATE 置 1
  - [x] 1.2 `AgentEntity` 增加 `isInternal` 字段
  - [x] 1.3 `AgentConfigProvider` 新增 `listAgentNames()`（返回 is_internal=0 的行名，异常返回空列表不抛出）
  - [x] 1.4 单测：多行/空表/异常三场景
- [x] Task 2: AgentRegistry 表驱动注册表（依赖 Task 1）
  - [x] 2.1 新增 `agent/AgentRegistry`：查表仅依赖 `AgentConfigProvider`（Dao 层）；`AgentService` 经 `ObjectProvider`/`@Lazy` 透传给 `GeneralAssistantAgent` 构造，注册表自身零调用；`multi-agent` 编排 bean 经注册方法预注入
  - [x] 2.2 启动注册：读 `listAgentNames()`，仅注册 `is_internal=0` 的行，逐行 try-catch（脏数据 warn 跳过不阻断启动）；`general` 行缺失时代码兜底注册（内置默认 prompt + 默认模型）并 warn 提示
  - [x] 2.3 惰性热注册：`require(name)` 未命中 → 查表（is_internal=0）→ `ConcurrentHashMap.computeIfAbsent` 原子构造注册；查表异常/构造失败按「未命中」处理 warn 后抛「未知 Agent」含可用列表
  - [x] 2.4 单测 `AgentRegistryTest`：启动自动注册/is_internal 排除/脏数据 fail-safe/general 兜底（含 DB 行优先、代码兜底不合并）/惰性注册/computeIfAbsent 并发单次构造/未命中报错文案
- [x] Task 3: 路由接入与 bean 收敛（依赖 Task 2）
  - [x] 3.1 `AgentServiceImpl` 改为委托 `AgentRegistry` 路由（submit/executeSync/executeStreamReactive/resumeStreamReactive/agentNames 全链路），删除 `List<Agent>` bean 注入构造
  - [x] 3.2 `ChatAgentConfig` 删除 `generalAgent`/`deepseekAgent` bean，新增 `AgentRegistry` bean；`multiAgent`/`toolLazyManager`/`graphCheckpointSaver` 不动
  - [x] 3.3 测试适配：`AgentServiceImplTest`（构造与 stub）、`ChatServiceImplTest` 等既有用例不回归；deepseek/nailong 类表行路由行为在测试中显式覆盖
- [x] Task 4: 回归与存档（依赖 Task 3）
  - [x] 4.1 全量 `mvn -s .mvn/settings.xml test` 通过
  - [x] 4.2 HARNESS_TODO.md 存档区注册口径更新（新增 Agent = agent 表一行，内部角色置 is_internal=1，运行时插行免重启；旧「bean+表行」口径修正）

# Task Dependencies

- Task 1 → Task 2 → Task 3 → Task 4（串行：注册表依赖数据层，路由接入依赖注册表，回归最后）
