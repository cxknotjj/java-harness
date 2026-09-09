package com.dark.javaHarness.domain.dto;

import java.util.List;

/**
 * 聊天接口响应体（POST /api/chat）。
 *
 * <p>{@code sources} 为 RAG 知识库最近一次命中的出处（docName/title/score）；
 * 知识库未启用/无命中时为 null 或空列表（向后兼容，旧客户端忽略未知字段）。
 */
public record ChatResponse(
        String sessionId,
        boolean newSession,
        String goalId,
        String status,
        String reply,
        String error,
        List<KnowledgeSource> sources) {

    /** 成功响应（无知识出处） */
    public static ChatResponse success(String sessionId, boolean newSession, String goalId, String reply) {
        return success(sessionId, newSession, goalId, reply, null);
    }

    /** 成功响应（携带知识出处） */
    public static ChatResponse success(String sessionId, boolean newSession, String goalId, String reply,
                                       List<KnowledgeSource> sources) {
        return new ChatResponse(sessionId, newSession, goalId, "SUCCEEDED", reply, null, sources);
    }

    /** 失败响应 */
    public static ChatResponse failure(String sessionId, boolean newSession, String goalId, String error) {
        return new ChatResponse(sessionId, newSession, goalId, "FAILED", null, error, null);
    }
}
