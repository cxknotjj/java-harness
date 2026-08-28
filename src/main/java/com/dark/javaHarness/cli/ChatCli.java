package com.dark.javaHarness.cli;

import com.dark.javaHarness.cli.api.ChatApiClient;
import com.dark.javaHarness.cli.render.TerminalRenderer;
import com.dark.javaHarness.domain.dto.ChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;

/**
 * 命令行聊天客户端：独立进程运行，通过 REST 调用主服务（8080）的 /api/chat/stream（SSE 流式）。
 *
 * 展示层（对标 Claude Code 的终端体验）由 {@link TerminalRenderer} 承担：
 * 阶段进度 spinner 原位刷新 + 完成折叠归档、工具调用行（⏺ 工具名(参数) → ✓ 耗时 · ±行数着色）、
 * 内容逐 token 流式 Markdown 着色、回合小结。
 *
 * 输入层：真实终端下由 JLine 3 接管——上下键翻阅输入历史（持久化到用户目录）、
 * `/` 命令自动补全菜单、多行粘贴；无 TTY 环境（如 exec:java 内嵌 JVM）自动降级为行式读取。
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
     * 当前选中的 Agent ID：null 表示交由服务端「主 Agent 前置判断」分流
     * （SIMPLE → general 单模型；COMPLEX → multi-agent 编排并推送进度事件）。
     * 注意：一旦非空（如默认带 1），服务端会绕过 RouteJudge 直接路由，
     * 永远走单 Agent 且无任何 progress 事件——所以默认必须保持 null。
     * 可用 /agent <id> 显式切换；/agent off 恢复分流。
     */
    private Long agentId = null;

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        LineInput input = openInput();
        ui = input.out();
        renderer.useOutput(ui);

        ui.println("==============================================");
        ui.println(" javaHarness CLI - 聊天客户端 (主服务: 8080)");
        ui.println(" 直接输入文本对话，/help 帮助，/exit 退出");
        ui.println(" ↑↓ 翻输入历史，Tab 补全命令");
        ui.println("==============================================");

        loadExistingSession();

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
        } else {
            send(line);
        }
        return false;
    }

    // ================================================================
    // 输入层：JLine（历史/补全/粘贴）+ 无 TTY 降级
    // ================================================================

    /** 行输入源抽象：read() 返回 null 表示 EOF；out() 为配套 UI 输出通道 */
    private interface LineInput {
        String read();

        /** 配套输出通道：JLine 实现返回 terminal.output()（宽字符，免代码页乱码）；降级实现走 stdout */
        default PrintStream out() {
            return System.out;
        }

        default void shutdown() {
        }
    }

    /** 优先 JLine 终端（真实 TTY）；dumb 终端（无键盘接管能力，如 exec:java 内嵌 JVM）降级行式读取 */
    private LineInput openInput() {
        try {
            org.jline.terminal.Terminal terminal = org.jline.terminal.TerminalBuilder.terminal();
            if (terminal.getType().contains("dumb")) {
                terminal.close();
                System.out.println("\033[90m（非交互终端，输入降级：无历史翻阅/补全；"
                        + "用 mvn -Pcli compile exec:exec 可获完整体验）\033[0m");
                return legacyInput();
            }
            return new JLineInput(terminal);
        } catch (Exception e) {
            System.out.println("\033[90m（终端初始化失败，输入降级: " + e.getMessage() + "）\033[0m");
            return legacyInput();
        }
    }

    /** JLine 行读取：上下键历史（持久化）+ `/` 命令补全 + 多行粘贴（bracketed paste） */
    private record JLineInput(org.jline.terminal.Terminal terminal,
                              org.jline.reader.LineReader reader) implements LineInput {

        JLineInput(org.jline.terminal.Terminal terminal) {
            this(terminal, org.jline.reader.LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(commandCompleter())
                    // 历史持久化到用户目录：跨进程保留，↑↓ 可翻阅
                    .variable(org.jline.reader.LineReader.HISTORY_FILE,
                            java.nio.file.Path.of(System.getProperty("user.home"), ".javaHarness_history"))
                    .build());
        }

        @Override
        public String read() {
            try {
                return reader.readLine("你> ");
            } catch (org.jline.reader.EndOfFileException e) {
                return null; // Ctrl+D / 流关闭
            } catch (org.jline.reader.UserInterruptException e) {
                return ""; // Ctrl+C：清空当前行继续
            }
        }

        /**
         * UI 输出经 {@link WriterBridge} 桥到 terminal.writer()：宽字符通道（WriteConsoleW），
         * 与控制台代码页（GBK/65001）无关，中文与 ✓/⏺ 永不乱码。
         * ⚠️ 不能用 terminal.output()：那是 jansi 字节通道，ANSI 序列被翻译但普通文本字节
         * 直传控制台按代码页解读——UTF-8 中文在 GBK 代码页必乱（LineReader 的提示符正常
         * 正是因为它走 writer()）。
         */
        @Override
        public PrintStream out() {
            return new PrintStream(new WriterBridge(terminal.writer()), true,
                    StandardCharsets.UTF_8);
        }

        @Override
        public void shutdown() {
            terminal.writer().flush();
        }

        /** `/` 命令补全：根命令直接列出，/agent 的参数补全 off */
        private static org.jline.reader.Completer commandCompleter() {
            return (reader, line, candidates) -> {
                String buffer = line.toString();
                String word = line.word().toString();
                if (buffer.stripLeading().startsWith("/agent")) {
                    if ("off".startsWith(word)) {
                        candidates.add(new org.jline.reader.Candidate("off"));
                    }
                    return;
                }
                if (!word.startsWith("/")) {
                    return;
                }
                for (String cmd : new String[]{"/help", "/new", "/agent", "/exit", "/quit"}) {
                    if (cmd.startsWith(word)) {
                        candidates.add(new org.jline.reader.Candidate(cmd));
                    }
                }
            };
        }
    }

    /**
     * PrintStream → Writer 桥：把 UTF-8 字节流经 CharsetDecoder 解码成字符，写入 JLine writer
     * （jansi 宽字符通道 WriteConsoleW，与控制台代码页无关）。
     * 不完整的多字节尾字符由 decoder 状态机保留，跨 write 调用安全。
     */
    private static final class WriterBridge extends java.io.OutputStream {

        private final java.io.Writer writer;
        private final java.nio.charset.CharsetDecoder decoder =
                StandardCharsets.UTF_8.newDecoder();
        private java.nio.ByteBuffer in = java.nio.ByteBuffer.allocate(1024);

        WriterBridge(java.io.Writer writer) {
            this.writer = writer;
        }

        @Override
        public synchronized void write(int b) {
            if (!in.hasRemaining()) {
                grow(in.capacity());
            }
            in.put((byte) b);
            drain();
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            if (len > in.remaining()) {
                grow(len);
            }
            in.put(b, off, len);
            drain();
        }

        /** 扩容到能容纳 need 字节（保留已缓冲内容） */
        private void grow(int need) {
            java.nio.ByteBuffer nio = java.nio.ByteBuffer
                    .allocate(Math.max(in.capacity() * 2, in.position() + need));
            in.flip();
            nio.put(in);
            in = nio;
        }

        /** 解码缓冲中所有完整字符；不完整多字节尾留 decoder（compact 后待后续补齐） */
        private void drain() {
            if (in.position() == 0) {
                return;
            }
            in.flip();
            while (in.hasRemaining()) {
                java.nio.CharBuffer out = java.nio.CharBuffer
                        .allocate(Math.max(16, in.remaining()));
                decoder.decode(in, out, false);
                out.flip();
                if (out.hasRemaining()) {
                    char[] chars = new char[out.remaining()];
                    out.get(chars);
                    try {
                        writer.write(chars);
                    } catch (IOException e) {
                        // 终端已不可写：输出静默丢弃（PrintStream 语义同为吞错）
                    }
                }
            }
            in.compact();
        }

        @Override
        public void flush() {
            try {
                writer.flush();
            } catch (IOException ignored) {
                // 同上
            }
        }

        @Override
        public void close() {
            flush();
        }
    }

    /** 降级输入：标准行式读取（无历史/补全，但任何环境可用） */
    private LineInput legacyInput() {
        java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(System.in));
        return () -> {
            try {
                ui.print("你> ");
                ui.flush();
                return br.readLine();
            } catch (IOException e) {
                return null;
            }
        };
    }

    /** 帮助信息（命令名青色着色） */
    private void printHelp() {
        String c = "\033[36m";
        String r = "\033[0m";
        ui.println("  直接输入文本与 AI 聊天（默认由服务端智能分流：简单→general / 复杂→multi-agent）");
        ui.println("  " + c + "/new" + r + "         新建会话（后续对话使用新上下文，旧会话保留）");
        ui.println("  " + c + "/agent <id>" + r + "  切换到指定 Agent（agent 表主键，此后不走分流）");
        ui.println("  " + c + "/agent off" + r + "   取消指定，恢复服务端自动分流");
        ui.println("  " + c + "/agent" + r + "       查看当前 Agent");
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
            ui.println("\033[90m已开启新会话 " + newId + "\033[0m");
        } catch (IOException e) {
            ui.println("新建会话失败: " + e.getMessage());
        }
    }

    /** 处理 /agent 命令：切换、查看或取消（off）当前 Agent */
    private void handleAgentCommand(String line) {
        String arg = line.substring("/agent".length()).trim();
        if (arg.isEmpty()) {
            ui.println("当前 Agent: " + (agentId == null ? "自动分流（服务端按复杂度选择）" : agentId));
            return;
        }
        if ("off".equalsIgnoreCase(arg)) {
            this.agentId = null;
            ui.println("已恢复服务端自动分流");
            return;
        }
        try {
            this.agentId = Long.parseLong(arg);
            ui.println("已切换到 Agent #" + agentId
                    + "（此后请求固定路由到该 Agent，不再自动分流；/agent off 可恢复）");
        } catch (NumberFormatException e) {
            ui.println("agent 编号无效，用法: /agent <数字Id> | /agent off | /agent");
        }
    }

    /** 发送一条消息到主服务 /api/chat/stream（SSE 流式）。
     *  展示全部委托给 TerminalRenderer：进度 spinner 原位刷新、token 流式 Markdown、回合小结。 */
    private void send(String message) {
        renderer.beginTurn();
        try {
            ChatResponse resp = api.chatStream(message, sessionId, agentId,
                    renderer::onToken,
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
                        renderer.onProgress(stage, detail);
                    });
            // 记住服务端返回的会话ID，后续请求携带以延续多轮上下文
            if (resp.sessionId() != null && !resp.sessionId().isBlank()) {
                this.sessionId = resp.sessionId();
            }
            boolean ok = "SUCCEEDED".equals(resp.status());
            renderer.endTurn(ok, ok ? null : resp.goalId() + " " +
                    (resp.error() == null ? "（无详细信息）" : resp.error()));
            if (ok) {
                ui.println("\033[90m（会话 " + sessionId + " / " + resp.goalId() + "）\033[0m");
            }
        } catch (ConnectException e) {
            renderer.endTurn(false, "无法连接主服务 " + baseUrl);
            ui.println("请先启动主进程: mvn -s .mvn/settings.xml spring-boot:run");
        } catch (IOException e) {
            renderer.endTurn(false, e.getMessage());
        }
    }
}