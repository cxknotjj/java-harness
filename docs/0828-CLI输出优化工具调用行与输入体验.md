# 0828 CLI 输出优化：工具调用行 + 变更 diff + JLine 输入体验

> 任务来源：HARNESS_TODO.md「CLI 输出优化（对标 Claude Code 的终端体验）」
> 本轮补齐此前未完成的三块：工具调用行、变更 diff 着色、输入体验（历史/补全/多行粘贴）。

## 1. 改动总览

| 层 | 文件 | 改动 |
|---|---|---|
| 服务端 | `tool/ToolCallTracer.java`（新增） | ToolCallback 追踪装饰器：调用前发 `tool`、调用后发 `tool-done` 进度行 |
| 服务端 | `agent/AgentChatCaller.java` | `call/stream/buildSpec` 增加工具事件发射器重载；追踪模式下双通道统一为装饰后回调单通道 |
| 服务端 | `agent/GeneralAssistantAgent.java` | 路径 A 流式执行以旁路 sink 合并工具事件（关闸挂 merge 前，沿用死锁教训） |
| 服务端 | `agent/MultiAgentGraphAgent.java` | 流式拓扑增加第三路旁路 `toolEvents`；`buildStateGraph(null,null,null)` 三参收敛 |
| CLI | `cli/render/TerminalRenderer.java` | `tool`→`⏺ 工具名(参数)` spinner；`tool-done`→归档着色结果行；新增 `cancelSpinner`/`archiveToolDone` |
| CLI | `cli/ChatCli.java` | JLine 3 输入层（历史/补全/粘贴）+ 无 TTY 降级；`LineInput` 抽象 |
| 构建 | `pom.xml` | 引入 `org.jline:jline:3.27.1`；新增 `-Pcli` profile（exec:exec fork 真实终端） |
| 测试 | `tool/ToolCallTracerTest.java`（新增） | 15 用例：事件组装/装饰行为/失败重抛/schema 透传 |
| 测试 | `cli/render/TerminalRendererTest.java` | 补工具行 2 用例（成功着色归档、失败 ✗） |
| 文档 | `docs/data-flow.md` | 「双通道」→「主干帧 + 三条旁路」；沙箱小节补追踪装饰层 |

## 2. 服务端：工具调用事件是怎么发出来的

### 2.1 ToolCallTracer 装饰器（核心设计）

装饰器透明包装 `ToolCallback`，模型完全无感：

```
模型请求工具执行
  → TracedToolCallback.call(input)
      → emit encode("tool", "WriteFile(/tmp/a.py)")     ← 起始事件（参数摘要）
      → delegate.call(input)                             ← 真实执行（沙箱工具/WebTools）
      → emit encode("tool-done", "WriteFile ✓ 1.2s · +12/-3 行")  ← 结果事件
      → 返回结果（异常则 emit ✗ 后原样抛出）
```

三条设计原则：

1. **schema 原样透传**：`getToolDefinition()` 直通 delegate，追踪对模型不可见，不影响工具选择；
2. **零开销直通**：emitter 为 null 时 `trace()` 返回原列表，同步 `execute` 等无进度通道的路径行为不变；
3. **复用既有线协议**：事件走 `ProgressLine.encode`（stage=tool / tool-done），与编排/拆解/子任务/聚合共用同一条 SSE 进度通道，CLI 与服务端无需新协议。

### 2.2 事件内容约定（单行，跨进程安全）

| stage | detail 示例 | 说明 |
|---|---|---|
| `tool` | `WriteFile(/tmp/a.py)` | 参数摘要：按 `path/file_path/url/command/code/query/dir/text` 候选键取第一个非空标量（截 60 字符），兜底截断原始 JSON |
| `tool-done` | `WriteFile ✓ 1.2s · +12/-3 行` | 成功：耗时 1 位小数 + diff 行数摘要 |
| `tool-done` | `RunShellCommand ✗ 0.3s` | 失败：无 diff |

diff 摘要为 **best-effort**：从入参 JSON 找旧值键（`old_string/old_text/oldStr/search`）与新值键（`new_string/new_text/newStr/replace/content/text`）——两者皆有 → `+新/-旧行数`；仅新值 → `+新行数`；找不到则省略。行数按 `\n` 计数 +1 近似。

### 2.3 接入链路（路径 A / B 通用）

```
子任务节点 subtask(state, idx, toolEmitter)      路径 B
  → predictSubtask → chatCaller.call(专家名, ..., emitter)
路径 A：GeneralAssistantAgent.executeStreamReactive
  → Sinks.Many<String> toolEvents（unicast + buffer）
  → buildChatRequestSpec(..., row -> tryEmitSerialized(toolEvents, row))
  → content.mergeWith(toolEvents.asFlux())        ← 与路径 B 通道一致，ChatServiceImpl 分流逻辑通用
```

要点：

- **追踪模式下双通道合一**：`@Tool` 注解对象经 `ToolCallbacks.from()` 转回调后与沙箱回调一起装饰，走 `spec.toolCallbacks(...)` 单通道（emitter 为 null 时保留原 `.tools()/.toolCallbacks()` 双通道，老路径零变化）；
- **并行安全**：多个子任务并行执行工具时，事件经 `BranchProgressListener.tryEmitSerialized`（Sink 锁）串行化发射，规避 `FAIL_NON_SERIALIZED` 静默丢事件；
- **关闸位置**：`doFinally(tryCompleteSerialized)` 挂在 merge **之前**的主干段——merge 要求两源都终结才传 complete，关闸挂 merge 后会循环等待（此前踩过的死锁，本轮沿用该结论）。

## 3. CLI 端：工具行渲染与着色

`TerminalRenderer.onProgress` 新增两个分支：

| stage | 行为 |
|---|---|
| `tool` | 起 spinner：`\| ⏺ WriteFile(/tmp/a.py) (2s)` 原位刷新 |
| `tool-done` | **静默折叠** spinner（只擦行、不产生 `✓ …` 归档，避免重复），随后归档着色结果行 |

结果行配色（对齐 Claude Code）：

```
⏺ WriteFile(/tmp/a.py) ✓ 1.2s · +12/-3 行     ← ⏺+参数摘要灰、✓ 绿、+12 绿、-3 红
⏺ RunShellCommand(python x.py) ✗ 0.3s         ← ✗ 红
```

连续多次工具调用天然折叠为单行紧凑列表（每次调用 = 一行灰色摘要），不刷屏。

## 4. 输入体验：JLine 3

### 4.1 引入理由（不可替代性）

- 请求的能力：上下键翻阅输入历史、`/` 命令 Tab 补全菜单、多行粘贴；
- 硬约束：Windows 控制台无纯 Java 的逐键 raw 输入，`BufferedReader` 行缓冲模式下方向键事件根本到不了 JVM——不引入终端库就无法实现；
- 选型：JLine 3 是事实标准（Spring Shell 同款）；uber jar 自带 jansi/jna/ffm 全部 Windows 终端 provider（已验证 jar 内容），单依赖即跨平台；仅 CLI 进程使用，服务端零依赖。

### 4.2 实现方式

- `ChatCli` 内 `LineInput` 抽象 + 两个实现：
  - `JLineInput`：`LineReader` + 命令 Completer（`/help /new /agent off /exit /quit`）+ 历史持久化 `~/.javaHarness_history`（跨进程保留），↑↓ 翻历史、Tab 补全、bracketed paste 多行粘贴；
  - 降级 `legacyInput`：标准行式读取——无 TTY 环境（`mvn exec:java` 内嵌 JVM）或终端初始化失败时自动回退，任何环境可用；
- 新增 `-Pcli` profile：`exec:exec` fork 独立 java 进程并继承当前终端，JLine 可完整接管按键。

### 4.3 启动方式变化

```bash
# 推荐：独立进程接管真实终端，历史/补全/粘贴完整可用
mvn -s .mvn/settings.xml -Pcli compile exec:exec

# 可用，但内嵌 JVM 无 TTY → 自动降级（提示已打印）
mvn -q -s .mvn/settings.xml exec:java
```

## 5. 验证

- 新增 `ToolCallTracerTest` 15 用例（含 `@Tool` 注解对象 → `ToolCallbacks.from` → 装饰链路的真实验证）；
- `TerminalRendererTest` 补 2 用例（成功 ±行数着色、失败 ✗）；
- 全量 `mvn test`：**19 个测试类、107 用例全部通过**。

## 6. 乱码修复（JLine 接管后的输出通道统一）

**现象**：引入 JLine 后，banner/会话提示中文乱码（`涓绘湇鍔?` 式 mojibake），但 `你>` 提示符正常。

**根因**：两套输出通道编码不一致——

- `System.out` 直写 UTF-8 字节 → GBK 代码页终端按 GBK 解读 → 乱码；
- JLine 的 `readLine("你> ")` 走 jansi 宽字符通道（`WriteConsoleW`），与代码页无关 → 正常。

**修复**：JLine 模式下统一输出通道到 **`terminal.writer()` 宽字符通道**——

1. `TerminalRenderer` 新增 `useOutput(PrintStream)`（输出流可重定向）；
2. `ChatCli` 引入 `ui` 字段与 `WriterBridge` 桥：PrintStream 写入的 UTF-8 字节经 `CharsetDecoder`
   解码成字符后交给 `terminal.writer()`（jansi 宽字符 `WriteConsoleW`，与控制台代码页无关）；
3. ⚠️ 走过弯路：先试了 `terminal.output()`——那是 jansi **字节通道**，ANSI 序列会被翻译，但普通文本
   字节直传控制台按代码页解读，UTF-8 中文在 GBK 代码页照样乱。判据：LineReader 提示符「你>」正常
   正是因为它走 writer()；
4. banner/帮助/会话提示/会话行等全部直接输出收敛到 `ui`，禁止再用 `System.out`；
5. `chatLoop` 顺序调整：先 `openInput()` 定输入源 → 再定 `ui` 与 renderer 输出 → 后打 banner。

**生效条件（踩坑）**：`mvn exec:java` 不在 compile 生命周期内，改完代码直接跑用的是旧 class——
必须 `mvn -s .mvn/settings.xml compile exec:java` 或 `-Pcli compile exec:exec`（带 compile）。

## 7. 已知限制

1. diff 行数摘要依赖常见键名候选匹配，agentscope 工具若使用其它字段名则省略 diff（不影响起止事件展示）；
2. 并行子任务的工具事件交错时，spinner 只显示最后起始的工具（结果行各自完整，信息不丢失）；
3. `exec:java` 内嵌 JVM 无 TTY，输入体验自动降级——完整体验请用 `-Pcli` 启动。
