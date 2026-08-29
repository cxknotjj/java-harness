package com.dark.javaHarness.enums;

/**
 * SSE 流式协议常量：服务端（ChatServiceImpl）与 CLI（ChatApiClient）跨进程共用的
 * 事件名、结束标记与内容行换行转义互逆方法。
 *
 * <p>集中此处避免双端魔法字符串漂移——任一端单独改协议字面量都会导致解析失联。
 */
public final class SseProtocol {

    private SseProtocol() {
    }

    /** SSE 事件名：元数据（sessionId/goalId/status/newSession） */
    public static final String EVENT_META = "meta";

    /** SSE 事件名：异常 */
    public static final String EVENT_ERROR = "error";

    /** SSE 事件名：执行进度（多 Agent 编排的阶段反馈） */
    public static final String EVENT_PROGRESS = "progress";

    /**
     * SSE 事件名：内容 token。必须显式声明——SSE 的 event 字段是粘滞的，
     * progress 块之后不带 event: 的 data 行会被客户端误归入上一事件
     * （曾导致工具调用后模型回复全部被当作 progress 吞掉、CLI 显示 0 字）。
     */
    public static final String EVENT_TOKEN = "token";

    /** token 流结束标记 */
    public static final String DONE_MARKER = "[DONE]";

    /**
     * 内容行传输转义（服务端发送侧）：裸换行会把一条 data 断成多个物理行，
     * 接收端只认前缀行会丢内容——反斜杠/换行符替换为字面量序列，
     * 接收端用 {@link #unescapeLineBreaks} 还原。
     */
    public static String escapeLineBreaks(String s) {
        return s.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }

    /**
     * 还原 {@link #escapeLineBreaks} 的转义（CLI 接收侧）：
     * 字面量 {@code \\} → 反斜杠、{@code \n} → 换行、{@code \r} → 回车；非法序列原样保留。
     */
    public static String unescapeLineBreaks(String s) {
        if (s == null || s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 'n') {
                    sb.append('\n');
                } else if (n == 'r') {
                    sb.append('\r');
                } else if (n == '\\') {
                    sb.append('\\');
                } else {
                    sb.append(c).append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
