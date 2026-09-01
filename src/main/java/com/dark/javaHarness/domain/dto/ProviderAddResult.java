package com.dark.javaHarness.domain.dto;

/**
 * 新增模型供应商结果（POST /api/providers 响应体）。
 *
 * @param added   新插入的映射行数
 * @param updated 因模型名已存在而更新的行数
 */
public record ProviderAddResult(int added, int updated) {

    /** 汇总描述（CLI 展示用） */
    public String summary() {
        return "新增 " + added + " 个映射，更新 " + updated + " 个";
    }
}
