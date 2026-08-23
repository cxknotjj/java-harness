package com.dark.javaHarness.dto;

/**
 * 统一异常响应体（全局异常处理器输出）。
 * code 为 HTTP 状态码，message 为可读错误信息。
 */
public record ErrorResponse(int code, String message) {

    public static ErrorResponse of(int code, String message) {
        return new ErrorResponse(code, message);
    }
}
