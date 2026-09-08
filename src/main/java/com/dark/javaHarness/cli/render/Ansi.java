package com.dark.javaHarness.cli.render;

/**
 * ANSI 转义常量（cli.render 包内共享）：回行首 + 擦除整行无需计算显示宽度，中文/符号通吃。
 */
final class Ansi {

    static final String RESET = "\033[0m";
    static final String BOLD = "\033[1m";
    static final String GRAY = "\033[90m";
    static final String RED = "\033[91m";
    static final String GREEN = "\033[32m";
    static final String CYAN = "\033[36m";
    static final String BLUE = "\033[94m";
    /** 回行首 + 擦除整行（无需计算显示宽度，中文/符号通吃） */
    static final String CLEAR_LINE = "\r\033[2K";

    private Ansi() {
    }
}
