package com.dark.javaHarness.cli.render;

/**
 * Markdown 行级 ANSI 着色（静态纯函数，便于单测）。
 * 从 TerminalRenderer 独立出的无状态渲染规则，不感知行缓冲与终端状态。
 */
final class MarkdownAnsiRenderer {

    private MarkdownAnsiRenderer() {
    }

    /**
     * 行内 Markdown → ANSI：标题粗蓝、列表符号青、{@code **粗体**}、{@code `行内代码`} 青。
     */
    static String renderInline(String line) {
        if (line.matches("#{1,6} .*")) {
            return Ansi.BOLD + Ansi.BLUE + line + Ansi.RESET;
        }
        if (line.matches("(?:-|\\*|\\+|\\d+\\.) .*")) {
            int sp = line.indexOf(' ');
            return Ansi.CYAN + line.substring(0, sp + 1) + Ansi.RESET + inlineSpans(line.substring(sp + 1));
        }
        return inlineSpans(line);
    }

    /** 行内片段：**bold** 与 `code` */
    private static String inlineSpans(String text) {
        return text.replaceAll("\\*\\*(.+?)\\*\\*", Ansi.BOLD + "$1" + Ansi.RESET)
                .replaceAll("`([^`]+)`", Ansi.CYAN + "$1" + Ansi.RESET);
    }

    /** 逻辑行渲染（含代码块状态，供残留行冲刷复用） */
    static String renderLogicalLine(String line, boolean inCodeBlock) {
        if (inCodeBlock) {
            return Ansi.CYAN + "│ " + line + Ansi.RESET;
        }
        return renderInline(line);
    }
}
