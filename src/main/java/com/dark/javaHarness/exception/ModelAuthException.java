package com.dark.javaHarness.exception;

import org.springframework.ai.retry.NonTransientAiException;

/**
 * 模型供应商鉴权失败（401 / invalid_api_key）。
 *
 * <p>首次使用最常见的失败形态：key 未配置、配错服务商（如 agent 绑在 DeepSeek 端点但只配了
 * QWEN_API_KEY）。这类错误重试无意义，应在调用链最内层立即识别并转换为携带
 * 「哪个模型、什么问题、用户怎么办」的可执行指引向上传播——与 {@link ModelQuotaException}
 * 同款约定，HTTP 路径由 {@link GlobalExceptionHandler} 统一映射 502。
 */
public class ModelAuthException extends RuntimeException {

    /** 涉及的模型名（可为 null：调用方未显式指定） */
    private final String model;
    /** 厂商返回的 HTTP 状态码 */
    private final int httpStatus;

    public ModelAuthException(String model, int httpStatus, String message) {
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
     * 判定异常链中是否包含鉴权失败（HTTP 401）。
     *
     * <p>Spring AI 对 4xx 错误统一抛 {@link NonTransientAiException}，message 格式为
     * {@code "<status> - <厂商响应体>"}，按前缀解析状态码；401 语义唯一（凭证无效），
     * 无需附加关键词匹配。
     */
    public static boolean matches(Throwable e) {
        NonTransientAiException nt = findNonTransient(e);
        return nt != null && nt.getMessage() != null && nt.getMessage().startsWith("401 ");
    }

    /** 从异常（含 cause 链）构造带可执行指引的鉴权失败异常；仅限 matches() 为 true 的异常调用 */
    public static ModelAuthException from(Throwable e, String model) {
        NonTransientAiException nt = findNonTransient(e);
        int status = parseStatus(nt == null ? null : nt.getMessage());
        String detail = nt == null || nt.getMessage() == null ? "" : brief(nt.getMessage());
        return new ModelAuthException(model, status,
                "模型 '" + (model == null ? "默认" : model) + "' 调用失败（HTTP " + status
                        + "）：鉴权失败——请配置对应服务商的 API key 环境变量（如 QWEN_API_KEY / "
                        + "DEEPSEEK_API_KEY）并重启服务，或核对 agent 绑定的服务商是否已配 key"
                        + "（详见 README 快速开始）" + (detail.isBlank() ? "" : "｜" + detail));
    }

    /* ---------- 内部工具（与 ModelQuotaException 同款口径） ---------- */

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
}
