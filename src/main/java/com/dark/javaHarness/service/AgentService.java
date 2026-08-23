package com.dark.javaHarness.service;

import com.dark.javaHarness.domain.Goal;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Agent 编排服务：接收一个请求，路由到对应 Agent 执行目标。
 */
public interface AgentService {

    /** 创建一个目标并异步派发给指定 Agent 执行。 */
    Goal submit(String agentName, String objective);

    /** 创建一个目标并同步执行（阻塞直至完成），适合聊天等需要立即拿到结果的场景。 */
    Goal executeSync(String agentName, String objective);

    /** 创建一个目标并同步执行，支持会话记忆（sessionId）。 */
    Goal executeSync(String agentName, String objective, String sessionId);

    /**
     * 创建一个目标并流式执行：逐片段（token）回调 onToken，返回完整执行摘要。
     * 同步返回前会阻塞直至整个流结束。
     */
    Goal executeStream(String agentName, String objective, String sessionId, Consumer<String> onToken);

    Set<String> agentNames();
}