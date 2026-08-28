package com.dark.javaHarness.domain;

/**
 * 一次 LLM 调用的观测记录（不可变值对象，由调用出口组装、LlmCallRecorder 落库）。
 *
 * @param sessionId       关联会话ID（路由判断等无会话场景为 null）
 * @param agentName       调用方角色（lead/researcher/aggregator/general/route-judge 等）
 * @param model           实际使用的模型名
 * @param stream          true-流式调用，false-阻塞调用
 * @param ok              true-成功，false-失败（errorMsg 有效）
 * @param promptTokens    输入 token（流式无 usage 回包时为 null）
 * @param completionTokens 输出 token（流式为近似估算）
 * @param totalTokens     总 token（流式为近似估算）
 * @param tokensEstimated token 是否为近似估算
 * @param durationMs      调用耗时（毫秒）
 * @param errorMsg        失败原因（成功为 null）
 */
public record LlmCallLog(String sessionId, String agentName, String model,
                         boolean stream, boolean ok,
                         Integer promptTokens, Integer completionTokens, Integer totalTokens,
                         boolean tokensEstimated,
                         long durationMs, String errorMsg) {
}
