# Checklist

## Prompt 组装管线（子项 4）
- [x] `PromptAssembler` 按固定次序组装段落（角色→工具索引→工具纪律→输出约定→skill）——PromptAssembler.java 内置五段 order 100→500，按序空行拼接
- [x] 两路径（GeneralAssistantAgent / AgentChatCaller）system prompt 统一经 Assembler 产出，散落拼接代码已删除——全局搜索「工具使用纪律」「专家 Agent」仅命中 PromptAssembler
- [x] 角色段优先级保持：agent 表 prompt > 角色内置兜底 > 默认 system prompt
- [x] skill 段扩展点存在且当前输出空串，可插拔不影响管线——SkillSectionProvider 接口全仓无实现
- [x] `PromptAssemblerTest` 覆盖段落次序/优先级/扩展点（8 用例）

## 记忆上下文动态注入（子项 5）
- [x] `general` 路径 A 记忆行为与现状一致，不回退——装配三行与改造前无差异
- [x] 编排 lead 注入会话记忆（与路径 A 同口径，预算受 ContextAssemblingAdvisor 裁剪）——memoryStore 同源 SessionService，advisor 在裁剪之前
- [x] 聚合节点与子任务专家均不注入记忆，token 消耗不随记忆增长——MemoryPolicy.MEMORY_ROLES 仅 lead/general，编排内 general 兜底专家亦不注入
- [x] 无会话 ID 场景（路由判定等）不挂载记忆 advisor，不报错
- [x] `MemoryPolicyTest` + 编排节点注入断言通过（lead 带 1 次 / 子任务与聚合 never）

## 工具 Schema 延迟加载（子项 6）
- [x] 开关开启时首轮请求工具参数 schema 置空（仅名称/描述），system 含工具索引段
- [x] `expand_tool(name)` 将工具加入会话级展开集合并返回完整参数说明
- [x] 已展开工具其后续请求按完整 schema 注入并可正常执行
- [x] 未展开工具被直接调用返回引导文本，不执行真实逻辑、不抛 No ToolCallback found——同请求内 expand 后自动放行（自愈）
- [x] 会话间展开集相互隔离；应用重启丢失可接受（重新 expand 即可）
- [x] 开关关闭时回退全量注入现状，expand_tool 不注册——app.prompt.lazy-tools.enabled 默认 true
- [x] `ToolLazyManagerTest` 覆盖上述全部场景（13 用例）

## 收尾
- [x] 全量 `mvn -s .mvn/settings.xml test` 通过（233 用例 0 失败，1 skip 为既有 MCP 项）
- [x] HARNESS_TODO.md 子项 4/5/6 勾选并存档实现要点，注明与子项 1/2/3 的衔接方式（skill 段扩展点接口 / purposeOf 元数据与工具索引段复用）
