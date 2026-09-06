package com.dark.javaHarness.domain.dto;

/**
 * 会话切换 Agent 结果视图：回显目标会话与切换后的 agentId（agentName 便于客户端直接展示）。
 */
public record SessionAgentView(String sessionId, Long agentId, String agentName) {
}
