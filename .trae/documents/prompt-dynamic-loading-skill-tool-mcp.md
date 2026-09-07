# Prompt 动态加载：skill / tool / mcp 动态装配

## Context

`HARNESS_TODO.md`「prompt的动态加载」六个子项中 4（角色段组装）、5（记忆注入）、6（工具 Schema 延迟加载）已完成，本次实现剩余三项：

1. **skill 动态装配**：`PromptAssembler` 已预留 skill 段扩展点 `SkillSectionProvider`（当前空实现），只需实现并注入
2. **tool 动态装配**：`ToolAssignments.forAgent` 目前是硬编码 switch（researcher/coder/analyst/general），改为数据驱动
3. **mcp 动态装配**：`McpToolProvider` 目前仅支持单 server（mcp-config.json 只取第一个条目）+ 硬编码 4 工具白名单

**用户已确认的设计**：
- skill：索引 + `load_skill` 元工具（与子项 6 `expand_tool` 同构，省 token）
- tool：agent 表新增 `tools` 列（与「新增 Agent = agent 表一行」哲学一致，改库即生效）
- mcp：多 server 支持（mcp-config.json 全量条目）+ 分配数据化

**关键现状**（实现时依赖）：
- `AgentConfigProvider.getAgentConfig` 每请求实时查库（无缓存）→ 数据驱动天然「改库即生效」
- `expand_tool` 先例（`ToolLazyManager`）：元工具不经 tracer/预算（无工具行噪声、不占执行额度）
- Flyway 最新迁移 **V9**，新迁移用 V10
- 构造参数追加遵循 lazyTools 接入先例：全参构造追加 + nullable 兜底 + 伸缩构造保测试兼容
- token 统计口径（`llm_call_log`）：阻塞调用有厂商真实 usage（promptTokens 隐含全部工具 schema/skill 索引开销）；流式调用 usage=null 仅按输出估算，prompt 侧开销不可见——流式增强见「第四部分」（已确认纳入）

## 实现步骤

### 第一部分：skill 动态装配

新增（`src/main/java/com/dark/javaHarness/prompt/`）：

1. **`SkillRepository`**（纯逻辑可测，非 bean 逻辑集中在 parse）：
   - 扫描 `app.prompt.skills.dir`（默认 `skills/`，相对工作目录），按文件 mtime 热重载（放文件/改文件下一请求生效）
   - 解析 Markdown front-matter（文件头 `---` 包围，逐行 `key: value` 手写解析，不引依赖）：
     - `name`（缺省=文件名去 .md）、`description`（缺省取正文首行）、`agents`（逗号分隔 agent 名，缺省=全部可见）
   - API：`List<Skill> skillsFor(String agentName)`、`Optional<Skill> find(name, agentName)`；`record Skill(name, description, agents, body)`；坏文件/坏 front-matter warn 跳过不抛
2. **`SkillManager`**（`@Component implements SkillSectionProvider`）：
   - `provide(agentName)` → 索引段：`可用技能（需要时调用 load_skill 获取完整说明）：` + `- name：description` 清单；该 agent 无可见技能 → 返回 null（skill 段自动跳过）
   - `Optional<ToolCallback> loadSkillTool(String agentName)`：`load_skill(skillName)` 元工具——校验 skillName ∈ 该 agent 可见技能集（越权返回拒绝文本），命中返回 Markdown 全文；预算口径：**不占工具次数额度、不经 tracer**（同 `expand_tool`，元数据操作不挤占真实工具额度），但**返回全文按 `tool-result-budget`（5000 token）同口径截断 + 截断标记**（超长技能防一次性灌爆上下文）
   - 开关 `app.prompt.skills.enabled`（默认 true），关闭时 provide 返回 null、loadSkillTool 返回 empty
3. **示例技能** `skills/web-research.md`（front-matter `agents: researcher, general`，正文为简短的网页调研纪律）——作为种子演示机制

注入链（与 lazyTools 完全同构）：

4. **`ChatAgentConfig`**：`PromptAssembler` 升级为共享 `@Bean`（参数：AgentService、ToolAssignments、`List<SkillSectionProvider>`（Spring 自动收集）、`toolLazyManager.isEnabled()`）；注入 general/multi-agent 两个 bean 与 `AgentRegistry`
5. **`GeneralAssistantAgent`**：全参构造追加 `PromptAssembler`、`SkillManager` 两参（null → 内部按现状裸构建）；`buildChatRequestSpec` 在 `lazyTools.process(...)` 之后追加 `skillManager.loadSkillTool(agentName)`
6. **`AgentChatCaller`**：全参构造追加 `SkillManager`（null → 无操作）；`buildSpec` 同点（`lazyTools.process` 之后）追加
7. **`MultiAgentGraphAgent`**：全参构造追加 `PromptAssembler`、`SkillManager`，透传给内部 `AgentChatCaller`；自身 `new PromptAssembler(...)` 换共享实例
8. **`AgentRegistry`**：构造追加两参 → 传给 `GeneralAssistantAgent`
9. **`application.yaml`**：`app.prompt.skills.{enabled: true, dir: skills}` + 注释

### 第二部分：tool 动态装配（agent 表 tools 列）

1. **迁移 `V10__agent_tools_column.sql`**：
   - `ALTER TABLE agent ADD COLUMN tools TEXT NULL COMMENT '分配的工具（逗号分隔：组名或工具名）'`
   - `UPDATE` 种子行保持**现有语义不变**（按 agent_name 定位）：
     - researcher: `web, sandbox.read, sandbox.browser, browser_click, browser_type, browser_press_key, browser_scroll`
     - coder: `sandbox.base, sandbox.write`
     - analyst: `sandbox.base, sandbox.read`
     - general: `web, sandbox.base, sandbox.read, sandbox.write, sandbox.browser, browser_click, browser_type, browser_press_key, browser_scroll`
2. **`AgentEntity`**：加 `tools` 字段；**`AgentConfigProvider`** 加 `findAgentTools(agentName)`（只查 tools 列，异常容错返回 empty）
3. **`ToolAssignments` 重构**：
   - 构造追加 `AgentConfigProvider`（`@Component` 用新构造；保留现有 3 参构造委托 null → 纯 legacy 路径，既有测试不破）
   - `forAgent(agentName)` 新逻辑：tools 列非 blank → **数据驱动**：token 按序解析——组名（`web`/`demo`/`sandbox.base`/`sandbox.read`/`sandbox.write`/`sandbox.browser`）展开为对应工具源；否则按精确工具名跨目录查找（web/demo @Tool 注解工具 → sandbox 各组 callbacks → `mcp.toolCallbacks()`）；未识别 token warn（按 token 去重）跳过
   - tools 列 blank/null → 回退**现有硬编码 switch**（含 MCP 白名单，现状语义兜底）
   - `purposeOf` 增强：未登记名回退 MCP callback 真实 description（动态发现的 MCP 工具在工具索引段也能显示用途）
4. **`ToolAssignmentsTest`** 扩展（见测试节）

### 第三部分：mcp 动态装配（多 server）

1. **`McpToolProvider` 重构**（公共 API `toolCallbacks()` 不变，`ToolAssignments` 无感）：
   - 新配置模型 `record ServerSpec(name, transport, url, command, args)`；来源：`mcp-config.json` 的 `mcpServers` **全量条目**——`command`(±`args`) → stdio、`url` → http、`enabled: false` 跳过；文件缺失/无有效条目 → 回退 legacy yaml 单 server（现逻辑平移，`loadStdioTarget` 保留）
   - 每 server 独立懒连接状态（client + cached callbacks）：沿用「后台预热 + 懒连接兜底 + 失败降级」，**失败按 server 隔离**（单 server 失败只贡献空列表 + warn，不拖垮其他 server）
   - `toolCallbacks()`：全 server 并集 + 按工具名去重（先到者优先，冲突 warn；不做 `mcp__server__tool` 前缀，保持引用简单）
   - `warmupAsync`：后台逐 server 预热；`shutdown`（@PreDestroy）关闭全部 client
2. **`application.yaml`** mcp 注释更新（多 server 说明）
3. **`McpToolProviderTest`** 扩展（见测试节）

### 第四部分：流式真实 usage 统计（已确认纳入）

**动机**：当前流式调用（几乎全部实际流量）usage=null，`llm_call_log` 只记输出估算值——工具 schema、skill 索引、load_skill 全文、会话历史等 prompt 侧开销在统计中不可见。

- 已核实：Spring AI 1.1.4 `OpenAiChatOptions.setStreamUsage(Boolean)` 支持 OpenAI `stream_options.include_usage`（DashScope 兼容模式支持），开启后流式最后一 chunk 携带真实 usage
- 改动点：
  - `GeneralAssistantAgent` / `AgentChatCaller` 的 options 构建处开启 `streamUsage(true)`（注意仅对支持的厂商开启，避免不识别该参数的端点报错——DashScope 兼容模式与 DeepSeek 均支持）
  - 流式收尾处从最后一个 chatResponse 读回 usage：有则记真实值（prompt/completion/total，`estimated=false`），无则维持现有估算兜底
  - `GeneralAssistantAgent.executeStreamReactive` 与 `AgentChatCaller`（call 背书通道/stream）三处记录点适配
- 验收：一次流式对话后 `llm_call_log` 出现非估算的真实 prompt/completion token，数值随工具 schema/skill 增减而变化
- 测试：记录点单测扩展（usage 回包优先、缺省回退估算）

### 测试

- 新增 **`SkillRepositoryTest`**：front-matter 解析/缺省回退（name=文件名、description=首行、agents 缺省全可见）/agents 过滤/mtime 热重载/坏文件容错
- 新增 **`SkillManagerTest`**：索引段渲染/无技能空段/load_skill 命中返回全文/越权拒绝/超长正文按 tool-result-budget 截断
- 扩展 **`ToolAssignmentsTest`**（mock AgentConfigProvider）：组名展开、精确名跨目录（含 MCP 工具）、混合、未知 token 跳过、blank 回退 legacy switch、purposeOf 回退
- 扩展 **`McpToolProviderTest`**：多条目解析（stdio+http 混合、enabled 跳过、legacy 回退）、失败隔离、同名去重
- 构造变更兼容：既有测试走伸缩构造（默认裸构建），预期不需大改

### 文档收尾

- **`HARNESS_TODO.md`**：勾选子项 1/2/3 + 「Prompt 动态装配」存档节追加四项（skill/tool/mcp/流式 usage）（遵循仓库存档惯例）
- 存档中注明：机制落地后 P0「工具分配最小权限化」收敛 general 权限 = 改一行 DB 数据即可

## 明确不做（scope 边界）

- 不改 general 权限语义（P0 最小权限化另立任务；本次种子数据保持现语义）
- 不做 MCP 工具名前缀/命名空间（重名先到优先 + warn）
- skill front-matter 只做简单 `key: value`（不引完整 YAML 语义）
- 不做 MCP 配置运行时热重载（连接/发现仍为懒连接 + 启动预热；改 mcp-config.json 需重启）

## 验证

1. 全量单测：`mvn -s .mvn/settings.xml test`（当前 268 用例全绿为基线）
2. 端到端（可选，需 MySQL + Docker 沙箱环境）：
   - 启动后 `skills/` 为空 → skill 段为空串（行为不变）；放入 `skills/web-research.md` → 下一请求 system prompt 出现技能索引，`load_skill` 可取全文（热装配）
   - `UPDATE agent SET tools='web' WHERE agent_name='general'` → 下一请求 general 工具面只剩 fetchUrl（改库即生效）
