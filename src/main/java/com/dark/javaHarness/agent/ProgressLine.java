package com.dark.javaHarness.agent;

/**
 * 「执行进度」行的线协议编解码，格式：{@code MARK + stage + SEPARATOR + detail}。
 * 发射方（MultiAgentGraphAgent）用 {@link #encode} 组装，消费方（ChatServiceImpl）
 * 用 {@link #isProgress} / {@link #decode} 识别进度与内容 token，避免魔法常量散落两处。
 */
public final class ProgressLine {

    /** 进度行前缀字符：防止与内容 token 混淆 */
    public static final char MARK = '\u0000';

    /** stage 与 detail 的分隔符 */
    public static final char SEPARATOR = '\u0001';

    private ProgressLine() {
    }

    /** 是否进度行（以 {@link #MARK} 开头） */
    public static boolean isProgress(String row) {
        return row != null && !row.isEmpty() && row.charAt(0) == MARK;
    }

    /** 组装一条进度行 */
    public static String encode(String stage, String detail) {
        return MARK + stage + SEPARATOR + detail;
    }

    /** 解析进度行返回「阶段 + 描述」；非进度行返回 null。 */
    public static StageRow decode(String row) {
        if (!isProgress(row)) {
            return null;
        }
        String body = row.substring(1);
        int sep = body.indexOf(SEPARATOR);
        return new StageRow(
                sep >= 0 ? body.substring(0, sep) : "",
                sep >= 0 ? body.substring(sep + 1) : body);
    }

    /** 解析结果：stage（阶段）+ detail（描述） */
    public record StageRow(String stage, String detail) {
    }
}
