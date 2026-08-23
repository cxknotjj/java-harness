package com.dark.javaHarness.dto;

/**
 * 聊天接口响应体（POST /api/chat）。
 */
public record ChatResponse(
        String sessionId,
        boolean newSession,
        String goalId,
        String status,
        String reply,
        String error) {

    /** 成功响应 */
    public static ChatResponse success(String sessionId, boolean newSession, String goalId, String reply) {
        return new ChatResponse(sessionId, newSession, goalId, "SUCCEEDED", reply, null);
    }

    /** 失败响应 */
    public static ChatResponse failure(String sessionId, boolean newSession, String goalId, String error) {
        return new ChatResponse(sessionId, newSession, goalId, "FAILED", null, error);
    }
}