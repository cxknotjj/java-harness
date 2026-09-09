package com.dark.javaHarness.cli;

import com.dark.javaHarness.cli.api.ChatApiClient;
import com.dark.javaHarness.cli.input.TerminalInput;
import com.dark.javaHarness.cli.render.TerminalRenderer;
import com.dark.javaHarness.domain.dto.ChatResponse;
import com.dark.javaHarness.domain.dto.KnowledgeSource;
import com.dark.javaHarness.domain.dto.ProviderAddResult;
import com.dark.javaHarness.domain.dto.ProviderRowView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * 命令行聊天客户端：独立进程运行，通过 REST 调用主服务（8080）的 /api/chat/stream（SSE 流式）。
 *
 * 展示层（对标 Claude Code 的终端体验）由 {@link TerminalRenderer} 承担：
 * 阶段进度 spinner 原位刷新 + 完成折叠归档、工具调用行（⏺ 工具名(参数) → ✓ 耗时 · ±行数着色）、
 * 内容逐 token 流式 Markdown 着色、回合小结。
 *
 * 输入层由 {@link TerminalInput} 承担：真实终端下由 JLine 3 接管——上下键翻阅输入历史（持久化到用户目录）、
 * `/` 命令自动补全菜单、多行粘贴；无 TTY 环境（如 exec:java 内嵌 JVM）自动降级为行式读取。
 * /resume 续跑目标的本地持久化由 {@link ResumeStateStore} 承担（跨 CLI 进程保留）。
 *
 * 重要：CLI 是纯 HTTP 客户端，不监听任何端口，占用的是你当前的终端进程。
 * 主服务（JavaHarnessApplication）负责监听 8080、保留日志、执行 Agent 编排。
 *
 * 启动方式（另开一个终端，在项目根目录）：
 *   mvn -s .mvn/settings.xml -Pcli compile exec:exec   ← 推荐：独立进程接管终端，历史/补全可用
 *   mvn -q -s .mvn/settings.xml exec:java              ← 可用，但无 TTY，输入体验降级
 *   终端需支持 ANSI 转义与 UTF-8（Windows Terminal / PowerShell 7 推荐）
 */
public class ChatCli {

    private final ChatApiClient api;
    private final String baseUrl;
    private final TerminalRenderer renderer = new TerminalRenderer();

    /**
     * 统一 UI 输出通道：JLine 模式下切到 terminal.output()（jansi 宽字符通道 WriteConsoleW，
     * 不受控制台代码页影响）；降级模式为 stdout（main 里已包装 UTF-8）。
     * 所有直接输出（banner/帮助/会话提示）必须走它，禁止直接用 System.out——
     * 否则 UTF-8 字节会被 GBK 代码页终端解读成乱码。
     */
    private PrintStream ui = System.out;

    /** 会话ID：首轮为空（由服务端自动建档），从首次响应中获取后复用，实现多轮记忆 */
    private String sessionId;

    /**
     * 当前会话最近一次复杂编排的 goalId（/resume 无参时的续跑目标）：
     * 编排流首的 goal 进度事件到达即记录（不等回合成功——CLI 断开前也已记录）；
     * 并持久化到 ~/.javaHarness_resume_state，CLI 重启后恢复（会话匹配才生效）。
     * /new 切换会话时清空并同步清持久化文件。普通单模型聊天不记录（无可续跑的检查点）。
     */
    private String lastOrchestratedGoalId;

    /** /resume 无参续跑目标的持久化存储（sessionId + goalId 两行，跨 CLI 进程保留） */
    private final ResumeStateStore resumeStore = new ResumeStateStore();

    /**
     * 当前选中的 Agent ID：null 表示交由服务端「主 Agent 前置判断」分流
     * （SIMPLE → general 单模型；COMPLEX → multi-agent 编排并推送进度事件）。
     * 注意：一旦非空（如默认带 1），服务端会绕过 RouteJudge 直接路由，
     * 永远走单 Agent 且无任何 progress 事件——所以默认必须保持 null。
     * 可用 /agent <id> 显式切换；/agent off 恢复分流。
     */
    private Long agentId = null;

    // 未知字段宽容：与服务端版本错开时（DTO 新增字段）仍可解析
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** 编排类进度 stage 集合：回合内出现任一即视为复杂编排回合（供 /resume 无参续跑判定）。
     *  goal = 编排流首下发的 goalId 事件（编排路径专属，出现即记录续跑目标） */
    private static final java.util.Set<String> STAGE_ORCHESTRATION =
            java.util.Set.of("goal", "编排", "拆解", "聚合", "子任务");

    /** 默认连本机主服务 8080 */
    public ChatCli() {
        this.baseUrl = "http://localhost:8080";
        this.api = new ChatApiClient(baseUrl);
    }

    /** 程序入口：启动交互式聊天循环 */
    public static void main(String[] args) {
        // 中文 Windows 控制台默认 GBK，强制 stdout 走 UTF-8，保证 ✓ 与框线符号不乱码
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out),
                true, StandardCharsets.UTF_8));
        new ChatCli().chatLoop();
    }

    /** 交互式循环（独立终端使用） */
    public void chatLoop() {
        // 先定输入源，再定输出通道（JLine 模式下 renderer 与 banner 全走 terminal.output() 防乱码）
        TerminalInput.LineInput input = TerminalInput.open();
        ui = input.out();
        renderer.useOutput(ui);

        ui.println("==============================================");
        ui.println(" javaHarness CLI - 聊天客户端 (主服务: 8080)");
        ui.println(" 直接输入文本对话，/help 帮助，/exit 退出");
        ui.println(" ↑↓ 翻输入历史，Tab 补全命令");
        ui.println("==============================================");

        loadExistingSession();
        restoreResumeState();

        while (true) {
            String line = input.read();
            if (line == null) {
                break;
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (dispatch(line)) {
                break;
            }
        }
        input.shutdown();
        ui.println("再见！");
    }

    /** 命令分发：返回 true 表示退出 */
    private boolean dispatch(String line) {
        if ("/exit".equals(line) || "/quit".equals(line)) {
            return true;
        }
        if ("/help".equals(line)) {
            printHelp();
        } else if ("/new".equals(line)) {
            handleNewSession();
        } else if (line.startsWith("/agent")) {
            handleAgentCommand(line);
        } else if (line.startsWith("/resume")) {
            handleResumeCommand(line);
        } else if (line.startsWith("/provider")) {
            handleProviderCommand(line);
        } else {
            send(line);
        }
        return false;
    }

    // ================================================================
    // 输入层已拆至 cli/input/TerminalInput（LineInput/JLineInput/WriterBridge/legacyInput）
    // ================================================================

    /** 帮助信息（命令名青色着色） */
    private void printHelp() {
        String c = "\033[36m";
        String r = "\033[0m";
        ui.println("  直接输入文本与 AI 聊天（默认由服务端智能分流：简单→general / 复杂→multi-agent）");
        ui.println("  " + c + "/new" + r + "         新建会话（后续对话使用新上下文，旧会话保留）");
        ui.println("  " + c + "/agent <id>" + r + "  切换到指定 Agent（agent 表主键，并同步当前会话的 agent_id）");
        ui.println("  " + c + "/agent off" + r + "   取消指定，恢复服务端自动分流");
        ui.println("  " + c + "/agent" + r + "       查看当前 Agent");
        ui.println("  " + c + "/resume" + r + "       断点续跑：恢复当前会话最近一次编排任务（从中断处继续）");
        ui.println("  " + c + "/resume <goalId>" + r + "  断点续跑指定的 goal（goalId 见每回合末尾的会话信息）");
        ui.println("  " + c + "/provider" + r + "      查看模型-服务商映射（model_provider 表全量）");
        ui.println("  " + c + "/provider add" + r + "  新增供应商：/provider add <provider> <apiUrl> <模型1,模型2,...>");
        ui.println("  " + c + "/exit" + r + "        退出");
    }

    /** 进入对话前，先调 /api/harness/sessions 取一个已有会话，后续 stream 请求携带其 sessionId 延续上下文 */
    private void loadExistingSession() {
        try {
            String existing = api.firstSessionId();
            if (existing != null && !existing.isBlank()) {
                this.sessionId = existing;
                ui.println("\033[90m已加载会话 " + sessionId + "\033[0m");
            } else {
                ui.println("\033[90m暂无历史会话，将新建会话\033[0m");
            }
        } catch (IOException e) {
            ui.println("获取会话列表失败（将新建会话）: " + e.getMessage());
        }
    }

    /** 处理 /new 命令：调用服务端新建空会话并切换当前会话（旧会话保留，可随时通过会话列表找回） */
    private void handleNewSession() {
        try {
            String newId = api.createSession();
            this.sessionId = newId;
            // 会话切换后「当前会话的任务」语义随之清空（含持久化文件）
            this.lastOrchestratedGoalId = null;
            persistResumeState();
            ui.println("\033[90m已开启新会话 " + newId + "\033[0m");
        } catch (IOException e) {
            ui.println("新建会话失败: " + e.getMessage());
        }
    }

    /** 处理 /agent 命令：切换、查看或取消（off）当前 Agent */
    private void handleAgentCommand(String line) {
        String arg = line.substring("/agent".length()).trim();
        if (arg.isEmpty()) {
            ui.println("当前 Agent: " + (agentId == null ? "自动分流（简单→会话绑定 Agent，复杂→multi-agent）" : agentId));
            return;
        }
        if ("off".equalsIgnoreCase(arg)) {
            this.agentId = null;
            ui.println("已恢复服务端自动分流");
            return;
        }
        try {
            long id = Long.parseLong(arg);
            // 先落库（服务端校验 agentId 存在性并更新 session 表 agent_id），成功后才更新内存选择。
            // 尚无会话（首轮未发消息）时仅记内存：下一轮服务端自动建档，请求携带的 agentId 会同步进会话表。
            if (sessionId != null) {
                try {
                    api.switchSessionAgent(sessionId, id);
                } catch (IOException e) {
                    ui.println("切换失败（会话 " + sessionId + " 未变更）: " + e.getMessage());
                    return;
                }
            }
            this.agentId = id;
            ui.println("已切换到 Agent #" + agentId
                    + (sessionId == null ? "（新会话建档时将登记该 Agent）" : "（会话 " + sessionId + " 已同步）")
                    + "；此后请求固定路由到该 Agent，不再自动分流；/agent off 可恢复");
        } catch (NumberFormatException e) {
            ui.println("agent 编号无效，用法: /agent <数字Id> | /agent off | /agent");
        }
    }

    /** 发送一条消息到主服务 /api/chat/stream（SSE 流式）。展示逻辑见 {@link #runTurn}。 */
    private void send(String message) {
        runTurn((onToken, onProgress) -> api.chatStream(message, sessionId, agentId, onToken, onProgress));
    }

    /**
     * 处理 /resume 命令：断点续跑未完成的复杂编排。
     * 带参 = 显式指定 goalId；无参 = 恢复当前会话最近一次编排任务（用户无需记住 goalId）。
     */
    private void handleResumeCommand(String line) {
        String arg = line.substring("/resume".length()).trim();
        if (arg.isEmpty()) {
            if (lastOrchestratedGoalId == null || lastOrchestratedGoalId.isBlank()) {
                ui.println("当前会话没有可续跑的编排任务（/resume <goalId> 可显式指定）");
                return;
            }
            arg = lastOrchestratedGoalId;
        }
        ui.println("\033[90m正在从检查点续跑 goal " + arg + "（已完成节点不再重跑）…\033[0m");
        final String goalId = arg;
        runTurn((onToken, onProgress) -> api.resumeStream(goalId, onToken, onProgress));
    }

    /**
     * 处理 /provider 命令：模型-服务商映射管理（model_provider 表）。
     * <pre>
     * /provider [list]                                    查看全量映射（含禁用行）
     * /provider add &lt;provider&gt; &lt;apiUrl&gt; &lt;模型1,模型2,...&gt;   新增映射并热刷新（免重启生效）
     * </pre>
     * 新增前需确保已设置对应环境变量 <PROVIDER大写>_API_KEY（如 MOONSHOT_API_KEY）。
     */
    private void handleProviderCommand(String line) {
        String arg = line.substring("/provider".length()).trim();
        if (arg.isEmpty() || "list".equalsIgnoreCase(arg)) {
            try {
                List<ProviderRowView> rows = api.listProviders();
                if (rows.isEmpty()) {
                    ui.println("暂无模型映射（model_provider 表为空）");
                    return;
                }
                ui.println("部署模型映射（id | 模型 → 供应商 | 端点，共 " + rows.size() + " 行）：");
                for (ProviderRowView r : rows) {
                    String flag = r.status() != null && r.status() == 1
                            ? "\033[32m✓\033[0m" : "\033[31m✗\033[0m";
                    ui.println("  " + flag + " [" + r.id() + "] " + r.model() + "  →  " + r.provider()
                            + "  \033[90m" + r.apiUrl() + "\033[0m");
                }
                ui.println("\033[90m提示：不同供应商可有同名模型（各自成行）；agent 按 id 绑定端点\033[0m");
            } catch (IOException e) {
                ui.println("获取映射失败: " + e.getMessage());
            }
            return;
        }
        if (arg.startsWith("add")) {
            String[] parts = arg.split("\\s+", 4);
            if (parts.length < 4) {
                ui.println("用法: /provider add <provider> <apiUrl> <模型1,模型2,...>");
                return;
            }
            String provider = parts[1];
            List<String> models = Arrays.stream(parts[3].split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (models.isEmpty()) {
                ui.println("至少提供一个模型名");
                return;
            }
            try {
                ProviderAddResult result = api.addProvider(provider, parts[2], models);
                ui.println("\033[32m✓ " + result.summary() + "，注册表已热刷新（免重启生效）\033[0m");
                ui.println("\033[90m提示：请确保已设置环境变量 " + provider.toUpperCase()
                        + "_API_KEY，否则该供应商模型将回退默认客户端\033[0m");
            } catch (IOException e) {
                ui.println("新增供应商失败: " + e.getMessage());
            }
            return;
        }
        ui.println("用法: /provider [list] | /provider add <provider> <apiUrl> <模型1,模型2,...>");
    }

    /** SSE 流式调用函数签名：一次调用产出完整回合（token/progress 回调 + 返回 meta） */
    @FunctionalInterface
    private interface SseCall {
        ChatResponse invoke(Consumer<String> onToken, Consumer<String> onProgress) throws IOException;
    }

    /**
     * 跑一个完整回合（send 与 /resume 共用）：进度 spinner 原位刷新、token 流式 Markdown、
     * 回合小结；记住服务端返回的会话ID延续多轮上下文。
     */
    private void runTurn(SseCall call) {
        renderer.beginTurn();
        // 回合内出现过编排进度（goal/编排/拆解/聚合）→ 复杂编排回合
        java.util.concurrent.atomic.AtomicBoolean orchestrated =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        // 流中见到的 goalId（goal 进度事件到达即记录——不等回合成功，断开前也已留存）
        java.util.concurrent.atomic.AtomicReference<String> seenGoalId =
                new java.util.concurrent.atomic.AtomicReference<>();
        try {
            ChatResponse resp = call.invoke(renderer::onToken,
                    data -> {
                        String stage = "";
                        String detail = data;
                        try {
                            var node = MAPPER.readTree(data);
                            stage = node.path("stage").asText("");
                            detail = node.path("detail").asText("");
                        } catch (IOException ignored) {
                            // 解析失败则 detail 原样展示，不中断
                        }
                        if ("goal".equals(stage) && !detail.isBlank()) {
                            // goalId 到达即记录并持久化：CLI 断开/重启后仍可 /resume
                            seenGoalId.set(detail);
                            this.lastOrchestratedGoalId = detail;
                            persistResumeState();
                        }
                        if (STAGE_ORCHESTRATION.contains(stage)) {
                            orchestrated.set(true);
                        }
                        renderer.onProgress(stage, detail);
                    });
            // 记住服务端返回的会话ID，后续请求携带以延续多轮上下文
            if (resp.sessionId() != null && !resp.sessionId().isBlank()) {
                this.sessionId = resp.sessionId();
                persistResumeState();
            }
            boolean ok = "SUCCEEDED".equals(resp.status());
            String goalId = resp.goalId() != null && !resp.goalId().isBlank()
                    ? resp.goalId() : seenGoalId.get();
            renderer.endTurn(ok, ok ? null : goalId + " " +
                    (resp.error() == null ? "（无详细信息）" : resp.error()));
            if (ok) {
                ui.println("\033[90m（会话 " + sessionId + " / " + goalId + "）\033[0m");
                // RAG 知识出处尾注（meta.sources：本次会话最近一次知识命中的文档；无命中不打印）
                List<KnowledgeSource> sources = resp.sources();
                if (sources != null && !sources.isEmpty()) {
                    StringBuilder sb = new StringBuilder("\033[90m来源: ");
                    for (int i = 0; i < sources.size(); i++) {
                        if (i > 0) {
                            sb.append(" · ");
                        }
                        KnowledgeSource s = sources.get(i);
                        sb.append("【出处").append(i + 1).append("】").append(s.docName());
                    }
                    ui.println(sb.append("\033[0m").toString());
                }
            }
        } catch (ConnectException e) {
            renderer.endTurn(false, "无法连接主服务 " + baseUrl);
            ui.println("请先启动主进程: mvn -s .mvn/settings.xml spring-boot:run");
        } catch (IOException e) {
            if (ChatApiClient.isGoalCompleted(e)) {
                // 服务端判定任务已完成（409 无需续跑）：清掉过期的本地续跑记录，下次启动不再提示
                this.lastOrchestratedGoalId = null;
                persistResumeState();
                renderer.endTurn(false, "该任务已完成，无需续跑（已清除本地续跑记录）");
                return;
            }
            renderer.endTurn(false, e.getMessage());
        }
    }

    // ================================================================
    // /resume 续跑目标的本地持久化（跨 CLI 进程保留）
    // ================================================================

    /** 把当前 sessionId + goalId 写入持久化文件（失败仅提示，不影响主流程） */
    private void persistResumeState() {
        try {
            resumeStore.save(sessionId, lastOrchestratedGoalId);
        } catch (Exception e) {
            ui.println("\033[90m（续跑状态持久化失败: " + e.getMessage() + "）\033[0m");
        }
    }

    /** CLI 启动时恢复持久化的续跑目标：会话匹配且服务端确认未完成才提示（已完成的直接清记录） */
    private void restoreResumeState() {
        if (lastOrchestratedGoalId != null || sessionId == null) {
            return;
        }
        try {
            ResumeStateStore.State state = resumeStore.load();
            if (state == null) {
                return;
            }
            String stateSessionId = state.sessionId();
            String stateGoalId = state.goalId();
            if (stateGoalId == null || stateGoalId.isBlank() || !sessionId.equals(stateSessionId)) {
                return;
            }
            // 向服务端确认任务未完成：避免「记录里的任务实际已跑完」时误导用户 /resume（撞 409）
            String status = null;
            try {
                status = api.goalStatus(stateGoalId);
            } catch (IOException ignored) {
                // 服务未就绪/网络失败：保留提示（/resume 真被拦截时也会自动清记录）
            }
            if ("SUCCEEDED".equals(status)) {
                this.lastOrchestratedGoalId = null;
                persistResumeState(); // 覆盖持久化文件，彻底清除过期记录
                ui.println("\033[90m上次编排任务已完成（服务端已确认），无需续跑\033[0m");
                return;
            }
            this.lastOrchestratedGoalId = stateGoalId;
            ui.println("\033[90m已恢复可续跑任务 " + stateGoalId + "（/resume 继续）\033[0m");
        } catch (Exception e) {
            ui.println("\033[90m（续跑状态恢复失败: " + e.getMessage() + "）\033[0m");
        }
    }
}