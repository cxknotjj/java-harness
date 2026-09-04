# Checklist

## 数据层（Task 1）
- [x] V9 迁移：is_internal 列（TINYINT NOT NULL DEFAULT 0）+ multi-agent/lead/aggregator 存量行置 1，应用启动自动应用
- [x] `AgentEntity` 含 isInternal 字段（MyBatis Plus 驼峰映射）
- [x] `listAgentNames()` 返回 is_internal=0 行名；空表/查询异常返回空列表不抛出
- [x] 单测覆盖多行/空表/异常三场景

## AgentRegistry（Task 2）
- [x] 启动时全部 is_internal=0 行自动注册为 `GeneralAssistantAgent`（name=行 agentName）
- [x] 排除逻辑完全由 is_internal 字段驱动，代码无硬编码角色清单
- [x] 启动注册逐行 try-catch：脏行 warn 跳过、其余照常、应用正常启动（fail-safe）
- [x] `general` 行缺失时代码兜底注册（内置默认配置）并 warn 提示；DB 行存在时完全按 DB 配置（两来源不合并）
- [x] 路由未命中 → 查表（is_internal=0）→ computeIfAbsent 原子构造注册；查表/构造异常按未命中处理，不泄漏底层异常
- [x] `AgentService` 仅经 ObjectProvider 透传构造参数，Registry 零方法调用，应用启动成功无循环依赖
- [x] `AgentRegistryTest` 9 用例覆盖启动注册/排除/fail-safe/兜底优先级/惰性/并发单次构造/报错文案

## 路由接入与 bean 收敛（Task 3）
- [x] `AgentServiceImpl` 全部路由方法委托 AgentRegistry，`agentNames()` 返回动态路由表（构造 @Lazy 解创建期循环）
- [x] `ChatAgentConfig` 不再手工注册 general/deepseek 对话 bean；multiAgent/toolLazyManager/graphCheckpointSaver 保持不动
- [x] `nailong` 类表行（is_internal=0）经 `/agent` 切换可正常路由，实例按行配置构造（AgentServiceImplTest 显式用例）
- [x] `AgentServiceImplTest` 适配且既有 `ChatServiceImplTest`/编排/SSE 用例零回归

## 收尾（Task 4）
- [x] 全量 `mvn -s .mvn/settings.xml test` 通过（249 用例 0 失败，1 skip 为既有 MCP 项）
- [x] HARNESS_TODO.md 注册口径存档更新（新增 Agent = agent 表一行；is_internal 语义；旧口径修正）
