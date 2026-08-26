package com.dark.javaHarness.enums;

/**
 * 跨类共享的 Agent 相关常量。
 *
 * <p>只归集「被多个类复用的业务常量」，避免散落重复字面量；
 * 各类内部私有实现常量（如 StateGraph 节点名 / 状态键、各自默认提示词等）
 * 仍保留在原类，不在此集中，以免破坏封装。
 */
public final class AgentConstants {

    private AgentConstants() {
    }

    /** 默认 / 兜底 Agent 名称（简单路径、未命中 agentId 时使用）。 */
    public static final String DEFAULT_AGENT = "general";
}