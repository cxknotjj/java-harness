package com.dark.javaHarness.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 聊天请求体（POST /api/chat、/api/chat/stream）。
 * message 必填，sessionId 可选（为空时自动创建新会话并在响应中返回）。
 */
public record ChatRequest(
        @NotBlank(message = "message 不能为空") String message,
        String sessionId) {
}