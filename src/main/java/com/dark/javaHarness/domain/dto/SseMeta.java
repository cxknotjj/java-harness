package com.dark.javaHarness.domain.dto;

/**
 * 流式聊天的 SSE meta 事件负载（末尾发送）。
 */
public record SseMeta(
        String sessionId,
        boolean newSession,
        String goalId,
        String status,
        String error) {
}