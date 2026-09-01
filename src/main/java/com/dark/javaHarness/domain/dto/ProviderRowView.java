package com.dark.javaHarness.domain.dto;

/**
 * 模型-服务商映射行视图（GET /api/providers 响应元素，CLI 渲染用）。
 * 服务端返回 ModelProviderEntity，CLI 侧用轻量 record 反序列化（时间字段不关心）。
 */
public record ProviderRowView(
        String model,
        String provider,
        String apiUrl,
        Integer status) {
}
