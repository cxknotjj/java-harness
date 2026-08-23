package com.dark.javaHarness.service;

import com.dark.javaHarness.domain.Goal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    Optional<Goal> getGoal(String id);

    List<Goal> allGoals();

    Set<String> agentNames();
}