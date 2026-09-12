package com.dark.javaHarness.domain;

/**
 * Agent 运行配置（部署模型 + 系统提示词 + 知识库绑定），来自 agent 表 JOIN model_provider。
 *
 * <p>modelProviderId 指向 model_provider.id（唯一确定"哪个端点的哪个模型"），
 * 用于从 {@code ChatClientRegistry} 取客户端；model 是该端点的模型名，
 * 作为请求级 model 参数发给厂商（同一模型在不同供应商下可有不同名称）。
 * knowledge 是 agent 表 knowledge 列原文（逗号分隔 kb 标识，解析在 KnowledgeRetriever），
 * null/空白 = 未绑定知识库（不做检索）。
 * 除 knowledge 外为空表示使用默认值（yaml 配置的默认客户端与模型）。
 */
public record AgentConfig(Long modelProviderId, String model, String prompt, String knowledge) {
}
