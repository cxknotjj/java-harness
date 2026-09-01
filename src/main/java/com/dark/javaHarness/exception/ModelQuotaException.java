package com.dark.javaHarness.exception;

import org.springframework.ai.retry.NonTransientAiException;

/**
 * 模型供应商账户级硬错误（余额不足 / 配额耗尽 / 访问被拒）。
 *
 * <p>这类错误重试无意义（厂商端账户状态问题，非瞬时故障），应在调用链最内层
 * 立即识别并转换为携带「哪个模型、什么问题、用户怎么办」的人话异常向上传播——
 * 编排路径降级为进度行展示，HTTP 路径由 {@link GlobalExceptionHandler} 统一映射 502。
 */
public class ModelQuotaException extends RuntimeException {

    /** 涉及的模型名（可为 null：调用方未显式指定） */
    private final String model;
    /** 厂商返回的 HTTP 状态码 */
    private final int httpStatus;

    public ModelQuotaException(String model, int httpStatus, String message) {
        super(message);
        this.model = model;
        this.httpStatus = httpStatus;
    }

    public String model() {
        return model;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /**
     * 判定异常链中是否包含账户级硬错误（402 余额不足 / 403 配额耗尽或访问被拒）。
     *
     * <p>Spring AI 对 4xx 错误统一抛 {@link NonTransientAiException}，message 格式为
     * {@code "<status> - <厂商响应体>"}，按前缀解析状态码；403 需额外匹配配额类关键词
     * （FreeTier / quota），避免把普通权限拒绝误报成余额问题。
     */
    public static boolean matches(Throwable e) {
        NonTransientAiException nt = findNonTransient(e);
        if (nt == null || nt.getMessage() == null) {
            return false;
        }
        String msg = nt.getMessage();
        if (msg.startsWith("402 ")) {
            return true;
        }
        return msg.startsWith("403 ")
                && (lowerContains(msg, "quota") || lowerContains(msg, "freetier"));
    }

    /** 从异常（含 cause 链）构造人话的账户错误异常；仅限 matches() 为 true 的异常调用 */
    public static ModelQuotaException from(Throwable e, String model) {
        NonTransientAiException nt = findNonTransient(e);
        int status = parseStatus(nt == null ? null : nt.getMessage());
        String hint;
        if (status == 402) {
            hint = "账户余额不足，请充值或更换模型";
        } else {
            hint = "配额耗尽或访问被拒，请充值、调整免费额度设置或更换模型";
        }
        String detail = nt == null || nt.getMessage() == null ? "" : brief(nt.getMessage());
        return new ModelQuotaException(model, status,
                "模型 '" + (model == null ? "默认" : model) + "' 调用失败（HTTP " + status
                        + "）：" + hint + (detail.isBlank() ? "" : "｜" + detail));
    }

    /* ---------- 内部工具 ---------- */

    /** 沿 cause 链查找 Spring AI 的 NonTransientAiException */
    private static NonTransientAiException findNonTransient(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof NonTransientAiException nt) {
                return nt;
            }
        }
        return null;
    }

    /** 从 "<status> - <body>" 格式解析状态码，解析失败返回 0 */
    private static int parseStatus(String message) {
        if (message == null || message.length() < 3) {
            return 0;
        }
        try {
            return Integer.parseInt(message.substring(0, 3));
        } catch (NumberFormatException nfe) {
            return 0;
        }
    }

    /** 截取厂商响应中的人类可读片段（原文太长只留前 120 字符），并去掉 "<status> - " 前缀 */
    private static String brief(String raw) {
        String body = raw;
        if (raw.length() >= 6 && raw.substring(0, 3).matches("\\d{3}") && raw.charAt(3) == ' ') {
            body = raw.substring(6).trim();
        }
        return body.length() > 120 ? body.substring(0, 120) + "…" : body;
    }

    private static boolean lowerContains(String haystack, String needle) {
        return haystack.toLowerCase().contains(needle);
    }
}
