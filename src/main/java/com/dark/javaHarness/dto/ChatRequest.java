package com.dark.javaHarness.dto;

/**
 * 聊天请求体（POST /api/chat）。
 * message 必填，sessionId 可选（为空时自动创建新会话并在响应中返回）。
 */
public record ChatRequest(String message, String sessionId) {
}