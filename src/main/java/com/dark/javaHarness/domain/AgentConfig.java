package com.dark.javaHarness.domain;

/**
 * Agent 运行配置（模型 + 系统提示词），来自 agent 表。
 * model / prompt 为空表示使用默认值（yaml 配置的模型 / Agent 内置默认提示词）。
 */
public record AgentConfig(String model, String prompt) {
}
