package com.dark.javaHarness.domain;

/**
 * 一条 MCP server 连接/发现事件的观测记录（不可变值对象，由 McpToolProvider 组装、
 * McpServerRecorder 落库）。
 *
 * @param serverName MCP server 名（mcp-config.json 条目名或 default）
 * @param transport  传输方式：stdio / http
 * @param event      CONNECTED-连接并发现完成 / FAILED-连接或发现失败
 * @param toolCount  发现的工具数（FAILED 时为 null）
 * @param errorMsg   失败原因（成功为 null）
 */
public record McpServerLog(String serverName, String transport, String event,
                           Integer toolCount, String errorMsg) {
}
