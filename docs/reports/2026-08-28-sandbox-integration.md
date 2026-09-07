# 0828 · Spring AI Alibaba Sandbox 接入与 Docker 验证

> 对应 HARNESS_TODO.md「Spring AI Alibaba Sandbox 接入」条目，本轮完成代码落地 + 单测 + Docker 真实验证 + 文档收尾。

## 一、任务背景

此前自研工具（`ShellTools`/`FileTools`）的黑名单+超时+目录沙箱只是**进程内软隔离**，模型生成的代码/命令仍跑在服务宿主机上。本轮引入 `spring-ai-alibaba-sandbox`（agentscope-runtime），把工具执行升级为**容器级隔离**。

**架构决策**：产品定位为通用助手（调研/报告/数据分析/跑代码），agent 不操作宿主机项目文件——模型生成的所有代码/命令只在容器内执行，宿主机零暴露；**不做降级路径**（沙箱是硬依赖，无 Docker 环境该功能整体不可用）。

## 二、变更清单

### 1. 依赖（pom.xml）

```xml
<!-- Spring AI Alibaba：Sandbox 容器级沙箱（agentscope-runtime，Docker 后端；版本由 BOM 管理） -->
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-sandbox</artifactId>
</dependency>
```

传递引入 `agentscope-runtime-sandbox-core:1.0.2`，与 Spring Boot 3.5.14 / Spring AI 1.1.4 兼容。

### 2. 新增 `SandboxToolProvider`

位置：`com.dark.javaHarness.tool.SandboxToolProvider`

- **懒初始化**：首次取用才拉起 Docker 容器，双检锁保证只尝试一次
- **失败降级**：初始化失败（如无 Docker）记录 warn，返回空工具面，进程内不重试，**绝不回退宿主机工具**
- **工具面三类**：
  - 执行类（base）：`RunPythonCodeTool` + `RunShellCommandTool`
  - 只读文件类：`ReadFileTool` / `ReadMultipleFilesTool` / `ListDirectoryTool` / `DirectoryTreeTool` / `SearchFilesTool` / `GetFileInfoTool`
  - 写入类：`WriteFileTool` / `EditFileTool` / `CreateDirectoryTool` / `MoveFileTool`
- **资源释放**：`@PreDestroy` 调 `SandboxService.close()`，容器随之删除

### 3. `ToolAssignments` 升级：双通道注入

- 新增 `ToolSet` 记录类：`annotated`（@Tool 对象，走 `.tools()`）+ `callbacks`（ToolCallback，走 `.toolCallbacks()`）
- 分配表（Sandbox 接入后）：
  | 专家 | @Tool 对象 | Sandbox ToolCallback |
  |------|-----------|---------------------|
  | researcher | WebTools | 只读文件类（2 个用例计数） |
  | coder | 无 | 执行类 + 写入类 |
  | analyst | 无 | 执行类 + 只读类 |
  | general | WebTools | 全量（执行+只读+写入） |
  | writer/未登记 | 无 | 空集（不触发沙箱初始化） |
- 权限边界是**服务端硬边界**：未分配的工具 schema 对模型不可见，服务端也无执行注册

### 4. 重合即退役（删除）

- 删除 `ToolSandbox` / `FileTools` / `SearchTools` / `ShellTools` 及对应测试类
- 保留 `WebTools`（Sandbox 未覆盖轻量网页抓取）、`DemoTools`（本地函数示例）

### 5. 新增测试

- `ToolAssignmentsTest`：5 用例，验证双通道分配语义、最小可见性、EMPTY 集合不触发沙箱初始化

### 6. 配置与工程文件

- `application.yaml`：无需新增沙箱配置（Docker host 默认 `localhost:2375` 失败后自动回退 Docker Desktop 标准 socket）
- `.gitignore`：新增 `sessions_mount_dir/`（agentscope-runtime 运行时自动创建的容器会话挂载目录）

## 三、验证过程与结果

### 1. 单测全量回归

**75/75 全部通过**（含新增 `ToolAssignmentsTest` 5 用例与 `MultiAgentGraphAgent` 钩子流式测试）。

### 2. 镜像预拉取

`BaseSandbox` 注册的镜像为 `agentscope-registry.ap-southeast-1.cr.aliyuncs.com/agentscope/runtime-sandbox-base:latest`（aliyun 新加坡 registry），Docker 29.7.2 下 `docker pull` 成功，**需预拉取**。

**这个镜像是什么**：它就是沙箱容器本身的运行时——容器内内置 fastapi 服务、Python/Shell 执行环境与文件工具实现，所有 agent 生成的 Python/Shell 都在这个容器里执行。工具调用链路：agent 调用工具 → `SandboxToolProvider.ensure()` 懒初始化 → `SandboxService` 用该镜像创建容器。没有镜像，容器创建直接失败，沙箱工具面为空。

**为什么要"预"拉取**：`SandboxToolProvider` 是懒初始化的，容器只在首个 agent 工具调用时才拉起。若不提前 pull，那次调用就要现场从 registry 下载数百 MB 镜像，agent 调用必然超时或卡住数分钟。预拉取后镜像常驻本地（验证日志 `Image found locally` 即命中本地镜像），之后每次创建容器只需秒级——拉一次、永久复用。

### 3. 真实沙箱链路验证（JShell 直连，不经 LLM）

验证脚本：`SandboxService.start()` → `new BaseSandbox(...)` → `runShellCommand("echo hello-sandbox && whoami")` → `svc.close()`。

结果：

- 容器成功拉起（镜像本地命中、容器端口 80 → 宿主 49152 映射）
- 容器内 Shell 执行成功：stdout=`hello-sandbox\nroot\n`，**returncode=0，isError=false**
- `close()` 后容器自动删除，端口释放，宿主机零残留
- 临时产物（`sessions_mount_dir/`、jar 解包目录）已清理

### 4. HARNESS_TODO.md 收尾

- 「Agent 工具库扩充」：更新为退役后现状（现存 WebTools/DemoTools，宿主机工具已退役）
- 「Spring AI Alibaba Sandbox 接入」：✅ 勾选，记录落地细节与验证结论
- 「工具分配最小权限化」：改造项适配沙箱语境（沙箱原生分执行/只读/写入三类，无需再拆类；剩余 general 收敛）

## 四、过程中遇到的问题与处理

| 问题 | 原因 | 处理 |
|------|------|------|
| `docker` 命令不识别 | Docker Desktop 刚装，终端 PATH 未刷新 | 从进程路径定位 `docker.exe`（`%LOCALAPPDATA%\Programs\DockerDesktop\resources\bin`），并前置 PATH |
| `docker-credential-desktop` 找不到 | 凭据助手同在 Docker bin 目录 | 同上，补 PATH 后 pull 成功 |
| `mvn test` 报 AccessDenied | 命令行默认走 `repository_zzyl` 仓库，被沙箱限制写入 | 用 `MAVEN_OPTS=-Dmaven.repo.local=...` 指向默认 m2（依赖此前已下载），`-D` 直传在沙箱包装下不生效 |
| `jshell -s-` 报「未知选项」 | 参数写法错误 | 改用 `-q` |
| 首次验证输出被截断，未看到 Shell 结果行 | 终端输出缓冲截断 | 重跑并落盘日志后确认 |
| 项目根出现 `sessions_mount_dir/` | agentscope-runtime 运行时自动创建容器挂载目录 | 验证后删除，并加入 `.gitignore` |

## 五、遗留事项

1. **真实 LLM 端到端**：模型生成 Python/Shell 落容器执行，待重启服务 + CLI 复杂任务验证（当前仅验证 Java→Docker 链路）
2. **浏览器自动化**（BrowserNavigateTool）补 web search 空缺
3. **MCP 桥接**衔接（沙箱内置 MCP 能力，独立迭代）
4. **general 权限收敛**：收敛为只读探索者，执行/写入只留给 lead 明确指派的 coder/analyst（见 TODO「工具分配最小权限化」条目）
5. web search 接入搜索服务商 API key；交互式高危命令审批通道

## 六、经验备忘

- 命令行构建统一用 `MAVEN_OPTS` 指定仓库，避免 `-D` 参数被终端包装层吞掉
- 沙箱镜像（aliyun 新加坡 registry）国内可直连拉取，但首次启动前建议预拉取
- agentscope-runtime 会在工作目录创建 `sessions_mount_dir/`，部署时注意工作目录选择或提前忽略
