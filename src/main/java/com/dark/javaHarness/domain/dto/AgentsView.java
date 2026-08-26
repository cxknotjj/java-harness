package com.dark.javaHarness.domain.dto;

import java.util.Set;

/**
 * Agent 列表响应（GET /api/harness/agents）。
 */
public record AgentsView(Set<String> agents) {
}