package com.dark.javaHarness.dto;

import java.util.Set;

/**
 * Agent 列表响应（GET /api/harness/agents）。
 */
public record AgentsView(Set<String> agents) {
}