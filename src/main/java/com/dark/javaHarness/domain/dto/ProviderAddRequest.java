package com.dark.javaHarness.domain.dto;

import java.util.List;

/**
 * 新增模型供应商请求体（POST /api/providers）。
 *
 * @param provider 服务商标识（如 moonshot / openrouter / siliconflow）
 * @param apiUrl   服务商端点 base-url（OpenAI 兼容地址）
 * @param models   该供应商下要注册的模型名列表（每个模型一行）
 * @param status   状态：1-启用（默认） 0-禁用，可空
 */
public record ProviderAddRequest(
        String provider,
        String apiUrl,
        List<String> models,
        Integer status) {

    /** 归一化后的启用状态：null/空 → 1 */
    public int resolvedStatus() {
        return status == null ? 1 : status;
    }
}
