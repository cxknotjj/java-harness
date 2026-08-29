package com.dark.javaHarness.cli.render;

import java.io.PrintStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 终端渲染器：对标 Claude Code 的过程展示体验（纯 ANSI 转义，零外部依赖）。
 *
 * <p>职责：
 * <ol>
 *   <li><b>阶段进度原位刷新</b>：spinner + 已耗时固定在底部状态行刷新；阶段完成折叠归档为
 *       灰色单行摘要（{@code ✓ 编排 · 3s}），不随内容滚动刷屏</li>
 *   <li><b>工具调用行</b>：{@code ⏺ 工具名(参数)} 执行中转 spinner，完成后归档
 *       {@code ✓ 耗时 · +N/-M 行}（diff 变更着色，+绿 / -红）</li>
 *   <li><b>内容逐 token 流式输出</b>：行缓冲 + 不完整行重绘——token 直出保证打字机效果，
 *       行完成后整行重绘升级为 Markdown 着色（标题/列表/粗体/行内代码/代码块）</li>
 *   <li><b>回合小结</b>：结束后输出耗时 / 子任务数 / 输出字数近似</li>
 * </ol>
 *
 * <p>线程模型：OkHttp 读线程顺序回调 {@code onProgress}/{@code onToken}；内部单线程定时器
 * 刷新 spinner。所有输出经同一把锁串行化，spinner 行是唯一可被擦除重写的「底部行」——
 * 任何内容输出前先折叠 spinner，保证状态行永不与正文交错。
 *
 * <p>ANSI 兼容性：需支持 VT 转义的现代终端（Windows Terminal / PowerShell 7 / 各类 *nix 终端）。
 */
public final class TerminalRenderer {

    // ---- ANSI 转义常量 ----
    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String GRAY = "\033[90m";
    private static final String RED = "\033[91m";
    private static final String GREEN = "\033[32m";
    private static final String CYAN = "\033[36m";
    private static final String BLUE = "\033[94m";
    /** 回行首 + 擦除整行（无需计算显示宽度，中文/符号通吃） */
    private static final String CLEAR_LINE = "\r\033[2K";

    private static final char[] SPINNER_FRAMES = {'|', '/', '-', '\\'};

    private final PrintStream defaultOut;
    /** 当前输出流：默认 stdout（UTF-8 包装）；JLine 模式下经 {@link #useOutput} 切到 terminal.output() */
    private PrintStream out;
    private final Object lock = new Object();
    private final ScheduledExecutorService spinnerTimer =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cli-spinner");
                t.setDaemon(true);
                return t;
            });

    // ---- spinner 状态 ----
    private boolean spinnerActive;
    private String spinnerStage = "";
    private String spinnerDetail = "";
    private long spinnerStartMs;
    private int spinnerTick;
    private ScheduledFuture<?> spinnerTask;

    // ---- 内容行缓冲状态 ----
    private final StringBuilder lineBuffer = new StringBuilder();
    private boolean inCodeBlock;

    // ---- 回合统计 ----
    private long turnStartMs;
    private int subtaskDone;
    private int contentChars;

    public TerminalRenderer() {
        this(System.out);
    }

    public TerminalRenderer(PrintStream out) {
        this.defaultOut = out;
        this.out = out;
    }

    /**
     * 运行时重定向输出流（JLine 模式必须）：terminal.output() 走 jansi 宽字符通道（WriteConsoleW），
     * 不受控制台代码页影响——否则 UTF-8 字节被 GBK 代码页终端解读必然乱码。
     */
    public void useOutput(PrintStream out) {
        synchronized (lock) {
            this.out = out == null ? defaultOut : out;
        }
    }

    /** 回合开始：重置统计（发送请求前调用） */
    public void beginTurn() {
        synchronized (lock) {
            turnStartMs = System.currentTimeMillis();
            subtaskDone = 0;
            contentChars = 0;
            lineBuffer.setLength(0);
            inCodeBlock = false;
        }
    }

    /**
     * 进度事件：按 stage 分派展示形态。
     * <ul>
     *   <li>编排/聚合 → spinner（阶段进行中，token 到达或下一事件时归档）</li>
     *   <li>拆解/子任务 → 直接归档为灰色 ✓ 摘要行（结果已产出）</li>
     * </ul>
     */
    public void onProgress(String stage, String detail) {
        synchronized (lock) {
            switch (stage == null ? "" : stage) {
                case "拆解" -> {
                    finishSpinnerAsDone();
                    archiveLine("✓ " + detail);
                }
                case "子任务" -> {
                    subtaskDone++;
                    finishSpinnerAsDone();
                    archiveLine("✓ " + detail);
                }
                // 工具调用行：起始转 spinner（⏺ 工具名(参数)），结果归档为着色摘要行
                case "tool" -> startSpinner("⏺ " + detail, "");
                case "tool-done" -> {
                    cancelSpinner();
                    archiveToolDone(detail);
                }
                // 杂散/空 stage 行（无阶段名的进度噪声，如 MCP 工具回放的残留）直接忽略，
                // 否则 startSpinner("") 会以空标题起 spinner，折叠时渲染成「✓  · 0s」的孤立空行
                default -> {
                    if (stage != null && !stage.isBlank()) {
                        startSpinner(stage, detail);
                    }
                }
            }
        }
    }

    /**
     * 内容 token：有 spinner 在转则先折叠归档（如聚合），随后行缓冲流式输出。
     */
    public void onToken(String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        synchronized (lock) {
            finishSpinnerAsDone();
            lineBuffer.append(token);
            contentChars += token.length();
            int idx;
            while ((idx = indexOfLineBreak(lineBuffer)) >= 0) {
                String line = lineBuffer.substring(0, idx);
                lineBuffer.delete(0, idx + 1);
                emitRenderedLine(line);
            }
            // 不完整行原样直出（打字机效果），整行完成后重绘升级着色
            if (lineBuffer.length() > 0) {
                out.print(CLEAR_LINE + lineBuffer);
            }
        }
    }

    /**
     * 回合结束：冲刷残留行，输出小结（成功：灰摘要；失败：红错误行）。
     */
    public void endTurn(boolean success, String error) {
        synchronized (lock) {
            if (lineBuffer.length() > 0) {
                out.print(CLEAR_LINE + renderLogicalLine(lineBuffer.toString()) + "\n");
                lineBuffer.setLength(0);
            }
            finishSpinnerAsDone();
            long sec = (System.currentTimeMillis() - turnStartMs) / 1000;
            if (success) {
                out.println(GRAY + "── 回合结束 · 耗时 " + sec + "s · 子任务 " + subtaskDone
                        + " 个 · 输出约 " + contentChars + " 字 ──" + RESET);
            } else {
                out.println(RED + "✗ 执行失败" + (error == null || error.isBlank()
                        ? "" : " · " + error) + RESET);
            }
        }
    }

    // ================================================================
    // spinner
    // ================================================================

    private void startSpinner(String stage, String detail) {
        // 空标题守卫：只有「无阶段名」的行才能进入，否则会折叠成空白 ✓ 行（防脏输出）
        if (stage == null || stage.isBlank()) {
            return;
        }
        finishSpinnerAsDone();
        spinnerStage = stage == null ? "" : stage;
        spinnerDetail = detail == null ? "" : detail;
        spinnerStartMs = System.currentTimeMillis();
        spinnerTick = 0;
        spinnerActive = true;
        refreshSpinner();
        spinnerTask = spinnerTimer.scheduleAtFixedRate(this::refreshSpinner, 120, 120, TimeUnit.MILLISECONDS);
    }

    private void refreshSpinner() {
        synchronized (lock) {
            if (!spinnerActive) {
                return;
            }
            long sec = (System.currentTimeMillis() - spinnerStartMs) / 1000;
            out.print("\r" + GRAY + SPINNER_FRAMES[spinnerTick++ % SPINNER_FRAMES.length]
                    + " " + spinnerStage + (spinnerDetail.isBlank() ? "" : " · " + spinnerDetail)
                    + " (" + sec + "s)" + RESET + "\033[K");
            out.flush();
        }
    }

    /** 停止 spinner 并归档为灰色摘要行；无活动 spinner 时不输出 */
    private void finishSpinnerAsDone() {
        if (!spinnerActive) {
            return;
        }
        if (spinnerTask != null) {
            spinnerTask.cancel(false);
            spinnerTask = null;
        }
        spinnerActive = false;
        long sec = (System.currentTimeMillis() - spinnerStartMs) / 1000;
        out.print(CLEAR_LINE + GRAY + "✓ " + spinnerStage + " · " + sec + "s" + RESET + "\n");
        out.flush();
    }

    /** 静默折叠 spinner：只擦行不归档（工具结果行自带摘要，无需重复 ✓ 行） */
    private void cancelSpinner() {
        if (!spinnerActive) {
            return;
        }
        if (spinnerTask != null) {
            spinnerTask.cancel(false);
            spinnerTask = null;
        }
        spinnerActive = false;
        out.print(CLEAR_LINE);
        out.flush();
    }

    /** 归档一行灰色摘要（结果型进度，无 spinner） */
    private void archiveLine(String text) {
        out.println(GRAY + text + RESET);
        out.flush();
    }

    /**
     * 工具结果行：{@code ⏺ WriteFile(/tmp/a.py) ✓ 1.2s · +12/-3 行}——
     * 调用摘要灰、✓ 绿 / ✗ 红、+N 绿 / -N 红（diff 变更着色，对齐 Claude Code）。
     */
    private void archiveToolDone(String detail) {
        int mark = detail.indexOf('✓');
        boolean ok = mark >= 0;
        if (!ok) {
            mark = detail.indexOf('✗');
        }
        if (mark < 0) {
            archiveLine("⏺ " + detail);
            return;
        }
        String call = detail.substring(0, mark).trim();
        String stat = detail.substring(mark + 1);
        String coloredStat = stat
                .replaceAll("\\+\\d+", GREEN + "$0" + GRAY)
                .replaceAll("-\\d+", RED + "$0" + GRAY);
        out.println(GRAY + "⏺ " + call + " " + (ok ? GREEN : RED) + detail.charAt(mark)
                + RESET + GRAY + coloredStat + RESET);
        out.flush();
    }

    // ================================================================
    // Markdown 行渲染
    // ================================================================

    /** 输出一条完整逻辑行：代码块围栏切换状态，其余行着色渲染；随后重写 partial */
    private void emitRenderedLine(String line) {
        if (line.trim().startsWith("```")) {
            inCodeBlock = !inCodeBlock;
            out.print(CLEAR_LINE + GRAY + line.trim() + RESET + "\n");
            return;
        }
        out.print(CLEAR_LINE + (inCodeBlock ? CYAN + "│ " + line + RESET : renderInline(line)) + "\n");
    }

    /** 逻辑行渲染（含代码块状态，供 endTurn 冲刷残留行） */
    private String renderLogicalLine(String line) {
        if (inCodeBlock) {
            return CYAN + "│ " + line + RESET;
        }
        return renderInline(line);
    }

    /**
     * 行内 Markdown → ANSI（静态纯函数，便于单测）：
     * 标题粗蓝、列表符号青、{@code **粗体**}、{@code `行内代码`} 青。
     */
    static String renderInline(String line) {
        if (line.matches("#{1,6} .*")) {
            return BOLD + BLUE + line + RESET;
        }
        if (line.matches("(?:-|\\*|\\+|\\d+\\.) .*")) {
            int sp = line.indexOf(' ');
            return CYAN + line.substring(0, sp + 1) + RESET + inlineSpans(line.substring(sp + 1));
        }
        return inlineSpans(line);
    }

    /** 行内片段：**bold** 与 `code` */
    private static String inlineSpans(String text) {
        String r = text.replaceAll("\\*\\*(.+?)\\*\\*", BOLD + "$1" + RESET)
                .replaceAll("`([^`]+)`", CYAN + "$1" + RESET);
        return r;
    }

    /** 兼容 \r\n 与 \n 的换行定位 */
    private static int indexOfLineBreak(StringBuilder sb) {
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (c == '\n' || c == '\r') {
                return i;
            }
        }
        return -1;
    }
}
