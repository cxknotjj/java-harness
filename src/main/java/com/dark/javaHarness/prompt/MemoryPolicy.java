package com.dark.javaHarness.prompt;

import java.util.Set;

/**
 * 记忆上下文动态注入策略：按角色名决定 LLM 请求是否携带会话记忆。
 *
 * <p>角色策略全集：
 * <ul>
 *   <li>{@code lead}（编排拆解）：注入——经编排调用器 AgentChatCaller 挂载，
 *       装配与路径 A 完全同口径（MessageChatMemoryAdvisor + ContextAssemblingAdvisor 预算裁剪）</li>
 *   <li>{@code general}（简单路径）：注入——路径 A 现状，由 GeneralAssistantAgent 自行装配，不经编排调用器</li>
 *   <li>{@code aggregator} 与子任务专家（researcher/coder/analyst/writer 等）：不注入——
 *       聚合忠实于各子任务结果，子任务上下文由 lead 在子任务描述中传递</li>
 * </ul>
 *
 * <p>无会话 ID 场景（路由判定等）：一律不注入。
 */
public class MemoryPolicy {

    /** 编排拆解角色（agent 表行名）：编排路径唯一注入会话记忆的角色 */
    private static final String ROLE_LEAD = "lead";

    /** 简单路径会话主角色：记忆注入为路径 A 现状 */
    private static final String ROLE_GENERAL = "general";

    /** 需要会话记忆的角色集合（策略全集） */
    private static final Set<String> MEMORY_ROLES = Set.of(ROLE_LEAD, ROLE_GENERAL);

    /** 角色策略判定：该角色是否需要会话记忆（不含会话 ID 维度） */
    public boolean shouldInject(String agentName) {
        return agentName != null && MEMORY_ROLES.contains(agentName);
    }

    /**
     * 可注入性判断（编排调用器 AgentChatCaller 的挂载依据）：仅 lead 且会话 ID 有效时注入。
     *
     * <p>编排内 {@code forAgent=general} 属子任务兜底专家身份（非路径 A 的会话主角色），
     * 子任务上下文由 lead 在子任务描述中传递，不注入；路径 A general 的记忆由
     * GeneralAssistantAgent 自行装配（现状），不经编排调用器。
     */
    public boolean shouldInject(String agentName, String sessionId) {
        return ROLE_LEAD.equals(agentName) && sessionId != null && !sessionId.isBlank();
    }
}
