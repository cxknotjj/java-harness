package com.dark.javaHarness.tool;

import java.util.function.Consumer;

/**
 * 工具装饰上下文：单次请求内装饰器链共享的只读差异项，
 * 由 AgentRequestSpecFactory 在装饰前构造一次。
 *
 * @param agentName         调用方角色（观测记录的 agent_name 列 / load_skill 可见性键）
 * @param sessionId         会话 ID（懒加载展开键 / 观测记录的 session_id 列）
 * @param emitter           SSE 进度行发射器（无 SSE 链路为 null，如 QQ 渠道/API 直调）
 * @param callBudgetEnabled 次数/结果硬预算开关（仅编排路径 true）
 */
public record ToolDecorationContext(String agentName,
                                    String sessionId,
                                    Consumer<String> emitter,
                                    boolean callBudgetEnabled) {
}
