package com.dark.javaHarness.cli.render;

import java.io.PrintStream;

/**
 * 终端渲染器：对标 Claude Code 的过程展示体验（纯 ANSI 转义，零外部依赖）。
 *
 * <p>职责：
 * <ol>
 *   <li><b>阶段进度原位刷新</b>：spinner 细节委托 {@link Spinner}，阶段完成折叠归档为
 *       灰色单行摘要（{@code ✓ 编排 · 3s}），不随内容滚动刷屏</li>
 *   <li><b>工具调用行</b>：{@code ⏺ 工具名(参数)} 执行中转 spinner，完成后归档
 *       {@code ✓ 耗时 · +N/-M 行}（diff 变更着色，+绿 / -红）</li>
 *   <li><b>内容逐 token 流式输出</b>：行缓冲 + 增量直出——只补打未上屏部分保证打字机效果
 *       （不依赖终端擦行重绘，规避 \r\033[2K 失效终端的整段重影）；
 *       着色行（标题/列表/粗体/行内代码/代码块）完成时才做一次整行重绘升级，
 *       行级着色规则委托 {@link MarkdownAnsiRenderer}</li>
 *   <li><b>回合小结</b>：结束后输出耗时 / 子任务数 / 输出字数近似</li>
 * </ol>
 *
 * <p>线程模型：OkHttp 读线程顺序回调 {@code onProgress}/{@code onToken}；{@link Spinner}
 * 内部单线程定时器刷新。所有输出经同一把锁串行化，spinner 行是唯一可被擦除重写的
 * 「底部行」——任何内容输出前先折叠 spinner，保证状态行永不与正文交错。
 *
 * <p>ANSI 兼容性：需支持 VT 转义的现代终端（Windows Terminal / PowerShell 7 / 各类 *nix 终端）。
 */
public final class TerminalRenderer {

    private final PrintStream defaultOut;
    /** 当前输出流：默认 stdout（UTF-8 包装）；JLine 模式下经 {@link #useOutput} 切到 terminal.output() */
    private PrintStream out;
    private final Object lock = new Object();
    private final Spinner spinner = new Spinner(lock, () -> out);

    // ---- 内容行缓冲状态 ----
    private final StringBuilder lineBuffer = new StringBuilder();
    /** 当前行已上屏的字符数：增量直出只补打未上屏部分，不依赖终端擦行重绘 */
    private int linePrinted;
    private boolean inCodeBlock;

    // ---- 回答归属前缀（agent 进度行驱动，与用户侧「你> 」提示符对称） ----
    /** 已着色的「agentName> 」前缀（首个回答 token 前打印一次） */
    private String answerPrefix;
    /** 前缀是否已上屏（后到的 agent 事件不再重复打印） */
    private boolean prefixPrinted;
    /** 前缀是否仍在当前光标行行首（该行完成重绘时须原样补回，否则被 CLEAR_LINE 擦掉） */
    private boolean prefixOnLine;

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
            linePrinted = 0;
            inCodeBlock = false;
            answerPrefix = null;
            prefixPrinted = false;
            prefixOnLine = false;
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
                    spinner.finishAsDone();
                    archiveLine("✓ " + detail);
                }
                case "子任务" -> {
                    subtaskDone++;
                    spinner.finishAsDone();
                    archiveLine("✓ " + detail);
                }
                // 工具调用行：起始转 spinner（⏺ 工具名(参数)），结果归档为着色摘要行
                case "tool" -> spinner.start("⏺ " + detail, "");
                case "tool-done" -> {
                    spinner.cancel();
                    archiveToolDone(detail);
                }
                // 回答归属：记录「agentName> 」前缀（不转 spinner），首个回答 token 前打印
                case "agent" -> {
                    if (detail != null && !detail.isBlank() && !prefixPrinted) {
                        answerPrefix = Ansi.BOLD + Ansi.CYAN + detail + "> " + Ansi.RESET;
                    }
                }
                // 杂散/空 stage 行（无阶段名的进度噪声，如 MCP 工具回放的残留）直接忽略，
                // 否则 startSpinner("") 会以空标题起 spinner，折叠时渲染成「✓  · 0s」的孤立空行
                default -> {
                    if (stage != null && !stage.isBlank()) {
                        spinner.start(stage, detail);
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
            spinner.finishAsDone();
            if (answerPrefix != null && !prefixPrinted) {
                out.print(answerPrefix);
                prefixPrinted = true;
                prefixOnLine = true;
            }
            lineBuffer.append(token);
            contentChars += token.length();
            int idx;
            while ((idx = indexOfLineBreak(lineBuffer)) >= 0) {
                // 先补打该行未上屏的后缀，再交 emitRenderedLine 收行（着色行会重绘整行）
                if (idx > linePrinted) {
                    out.print(lineBuffer.substring(linePrinted, idx));
                }
                String line = lineBuffer.substring(0, idx);
                lineBuffer.delete(0, idx + 1);
                emitRenderedLine(line);
                linePrinted = 0;
            }
            // 不完整行增量直出（打字机效果）：只补打未上屏部分。原先每 token 擦行重绘整行，
            // 依赖终端正确处理 \r\033[2K——部分终端不生效时每次重绘都留在屏上，
            // 表现为「同一段文字带渐长尾巴重复」的整段重影
            if (lineBuffer.length() > linePrinted) {
                out.print(lineBuffer.substring(linePrinted));
                linePrinted = lineBuffer.length();
            }
        }
    }

    /**
     * 回合结束：冲刷残留行，输出小结（成功：灰摘要；失败：红错误行）。
     */
    public void endTurn(boolean success, String error) {
        synchronized (lock) {
            if (lineBuffer.length() > 0) {
                String rendered = MarkdownAnsiRenderer.renderLogicalLine(lineBuffer.toString(), inCodeBlock);
                if (rendered.equals(lineBuffer.toString())) {
                    // 纯文本残留：补打未上屏部分后换行，免擦行重绘
                    if (lineBuffer.length() > linePrinted) {
                        out.print(lineBuffer.substring(linePrinted));
                    }
                    out.print("\n");
                    prefixOnLine = false;
                } else {
                    out.print(Ansi.CLEAR_LINE + withAnswerPrefix() + rendered + "\n");
                }
                lineBuffer.setLength(0);
                linePrinted = 0;
            }
            spinner.finishAsDone();
            long sec = (System.currentTimeMillis() - turnStartMs) / 1000;
            if (success) {
                out.println(Ansi.GRAY + "── 回合结束 · 耗时 " + sec + "s · 子任务 " + subtaskDone
                        + " 个 · 输出约 " + contentChars + " 字 ──" + Ansi.RESET);
            } else {
                out.println(Ansi.RED + "✗ 执行失败" + (error == null || error.isBlank()
                        ? "" : " · " + error) + Ansi.RESET);
            }
        }
    }

    /** 归档一行灰色摘要（结果型进度，无 spinner） */
    private void archiveLine(String text) {
        out.println(Ansi.GRAY + text + Ansi.RESET);
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
                .replaceAll("\\+\\d+", Ansi.GREEN + "$0" + Ansi.GRAY)
                .replaceAll("-\\d+", Ansi.RED + "$0" + Ansi.GRAY);
        out.println(Ansi.GRAY + "⏺ " + call + " " + (ok ? Ansi.GREEN : Ansi.RED) + detail.charAt(mark)
                + Ansi.RESET + Ansi.GRAY + coloredStat + Ansi.RESET);
        out.flush();
    }

    /** 输出一条完整逻辑行：代码块围栏切换状态；着色行整行重绘升级，纯文本行免重绘直接换行 */
    private void emitRenderedLine(String line) {
        if (line.trim().startsWith("```")) {
            inCodeBlock = !inCodeBlock;
            out.print(Ansi.CLEAR_LINE + withAnswerPrefix() + Ansi.GRAY + line.trim() + Ansi.RESET + "\n");
            return;
        }
        String rendered = inCodeBlock ? Ansi.CYAN + "│ " + line + Ansi.RESET : renderInline(line);
        if (rendered.equals(line)) {
            // 纯文本行（含已在屏上的前缀）无着色收益，补打部分已先行输出，直接换行——
            // 免一次擦行重绘，普通聊天（大量纯文本）在擦行失效的终端上零重影
            out.print("\n");
            prefixOnLine = false;
            return;
        }
        out.print(Ansi.CLEAR_LINE + withAnswerPrefix() + rendered + "\n");
    }

    /** 当前光标行行首若挂着回答前缀（首次回答行），擦行重绘时须原样补回 */
    private String withAnswerPrefix() {
        if (prefixOnLine) {
            prefixOnLine = false;
            return answerPrefix == null ? "" : answerPrefix;
        }
        return "";
    }

    /** 行内 Markdown → ANSI（委托 {@link MarkdownAnsiRenderer}；静态入口为既有测试直连保留） */
    static String renderInline(String line) {
        return MarkdownAnsiRenderer.renderInline(line);
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
