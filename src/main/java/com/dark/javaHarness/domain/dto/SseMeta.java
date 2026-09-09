package com.dark.javaHarness.domain.dto;

import java.util.List;

/**
 * 流式聊天的 SSE meta 事件负载（末尾发送）。
 *
 * <p>{@code sources} 为 RAG 知识库本次会话最近命中的出处（docName/title/score）；
 * 知识库未启用/无命中时为 null（旧客户端忽略未知字段）。
 */
public record SseMeta(
        String sessionId,
        boolean newSession,
        String goalId,
        String status,
        String error,
        List<KnowledgeSource> sources) {
}
