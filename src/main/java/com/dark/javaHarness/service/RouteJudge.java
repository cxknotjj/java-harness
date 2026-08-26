package com.dark.javaHarness.service;

import com.dark.javaHarness.domain.RouteDecision;

/**
 * 主 Agent 前置判断（路由判断器）。
 *
 * <p>职责单一：仅做「分流」，判断一条用户请求属于简单(场景A)还是复杂(场景B)，
 * 输出 {@link RouteDecision}；不执行具体任务，保持入口薄。
 * 复杂路径（多 Agent Graph）就绪后，由调用方依据返回值切换执行链路。
 */
public interface RouteJudge {

    /** 判断一条用户请求应走哪条路径。内部应保证对调用方不抛异常（失败兜底 SIMPLE）。 */
    RouteDecision judge(String message);
}