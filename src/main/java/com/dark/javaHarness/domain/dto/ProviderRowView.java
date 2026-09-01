package com.dark.javaHarness.domain.dto;

/**
 * 模型-服务商映射行视图（GET /api/providers 响应元素，CLI 渲染用）。
 * id 为部署模型主键（agent.model_provider_id 引用它），CLI 展示列 + /agent 绑定参考。
 * 服务端返回 ModelProviderEntity，CLI 侧用轻量 record 反序列化（时间字段不关心）。
 */
public record ProviderRowView(
        Long id,
        String model,
        String provider,
        String apiUrl,
        Integer status) {
}
