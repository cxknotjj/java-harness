package com.dark.javaHarness.domain;

/**
 * 一次工具执行的观测记录（不可变值对象，由 ToolCallTracer 装饰器组装、LlmCallRecorder 落库）。
 *
 * @param sessionId   关联会话ID（无会话场景为 null）
 * @param agentName   调用方角色（lead/researcher/aggregator/general/qq-channel 等）
 * @param toolName    工具名
 * @param serverName  来源 MCP server 名（非 MCP 工具为 null）
 * @param argsSummary 参数摘要（候选键值或截断原文）
 * @param ok          true-成功，false-失败（errorMsg 有效）
 * @param durationMs  调用耗时（毫秒）
 * @param errorMsg    失败原因（成功为 null）
 */
public record ToolCallLog(String sessionId, String agentName, String toolName, String serverName,
                          String argsSummary, boolean ok, long durationMs, String errorMsg) {
}
