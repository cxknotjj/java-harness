package com.dark.javaHarness.domain;

/**
 * 主 Agent 前置判断的路由决策。
 *
 * <p>只表达「当前请求应走哪条路径」，不携带任何执行逻辑：
 * - {@link #SIMPLE}：简单(场景A)，无需工具/拆分子任务，单次 LLM 调用即可。
 * - {@link #COMPLEX}：复杂(场景B)，需搜索/代码/多步骤处理，走多 Agent 编排。
 */
public enum RouteDecision {

    SIMPLE,
    COMPLEX;

    /**
     * 由 LLM 返回的 route 文本归一化为枚举。
     * 非 complex 的任意值（含 null、"simple"、乱码）一律归一为 {@link #SIMPLE}，
     * 符合「宁可简单，不强行走复杂流程」的兜底原则（TODO ⑤）。
     */
    public static RouteDecision fromRaw(String raw) {
        if (raw != null && "complex".equalsIgnoreCase(raw.trim())) {
            return COMPLEX;
        }
        return SIMPLE;
    }
}