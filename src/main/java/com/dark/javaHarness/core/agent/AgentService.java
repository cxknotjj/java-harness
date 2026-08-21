package com.dark.javaHarness.core.agent;

import com.dark.javaHarness.core.goal.Goal;
import com.dark.javaHarness.core.goal.GoalManager;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Agent 编排服务：接收一个请求，路由到对应 Agent，
 * 异步执行目标并回写 Goal 生命周期状态（RUNNING -> SUCCEEDED/FAILED）。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final GoalManager goalManager;
    private final Map<String, Agent> agents;

    public AgentService(GoalManager goalManager, List<Agent> agentList) {
        this.goalManager = goalManager;
        // 以 name() 为 key 建立路由表
        Map<String, Agent> map = new java.util.concurrent.ConcurrentHashMap<>();
        for (Agent agent : agentList) {
            map.put(agent.name(), agent);
        }
        this.agents = map;
    }

    /**
     * 创建一个目标并异步派发给指定 Agent 执行。
     * @return 创建的 Goal id
     */
    public Goal submit(String agentName, String objective) {
        Agent agent = requireAgent(agentName);
        Goal goal = goalManager.create(objective);
        CompletableFuture.runAsync(() -> run(goal, agent));
        return goal;
    }

    /**
     * 创建一个目标并同步执行（阻塞直至完成），适合聊天这类需要立即拿到结果的场景。
     * @return 执行完毕的 Goal（含 SUCCEEDED/FAILED 状态与 summary）
     */
    public Goal executeSync(String agentName, String objective) {
        return executeSync(agentName, objective, null);
    }

    /**
     * 创建一个目标并同步执行（阻塞直至完成），支持会话记忆（sessionId）。
     * @return 执行完毕的 Goal（含 SUCCEEDED/FAILED 状态与 summary）
     */
    public Goal executeSync(String agentName, String objective, String sessionId) {
        Agent agent = requireAgent(agentName);
        Goal goal = goalManager.create(objective, sessionId);
        run(goal, agent);
        return goal;
    }

    private Agent requireAgent(String agentName) {
        Agent agent = agents.get(agentName);
        if (agent == null) {
            throw new IllegalArgumentException("未知 Agent: " + agentName + "，可用: " + agents.keySet());
        }
        return agent;
    }

    private void run(Goal goal, Agent agent) {
        goal.markRunning();
        try {
            String summary = agent.execute(goal);
            goal.succeed(summary);
            log.info("[{}] goal '{}' SUCCEEDED -> {}", goal.id(), goal.objective(), summary);
        } catch (Exception e) {
            // 目标失败是正常业务结果（如未配置 API key 时的 401），只记一行摘要避免刷屏
            log.warn("[{}] goal '{}' FAILED: {}", goal.id(), goal.objective(), e.getMessage());
            goal.fail(e.getMessage());
        }
    }

    public java.util.Optional<Goal> getGoal(String id) {
        return goalManager.get(id);
    }

    public List<Goal> allGoals() {
        return goalManager.all();
    }

    public java.util.Set<String> agentNames() {
        return agents.keySet();
    }
}