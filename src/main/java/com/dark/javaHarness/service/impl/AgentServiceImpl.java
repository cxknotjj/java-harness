package com.dark.javaHarness.service.impl;

import com.dark.javaHarness.agent.Agent;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.GoalService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Agent 编排服务实现：接收一个请求，路由到对应 Agent，
 * 执行目标并回写 Goal 生命周期状态（RUNNING -> SUCCEEDED/FAILED）。
 */
@Service
public class AgentServiceImpl implements AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentServiceImpl.class);

    private final GoalService goalService;
    private final ConcurrentMap<String, Agent> agents;

    public AgentServiceImpl(GoalService goalService, List<Agent> agentList) {
        this.goalService = goalService;
        // 以 name() 为 key 建立路由表
        ConcurrentMap<String, Agent> map = new ConcurrentHashMap<>();
        for (Agent agent : agentList) {
            map.put(agent.name(), agent);
        }
        this.agents = map;
    }

    @Override
    public Goal submit(String agentName, String objective) {
        Agent agent = requireAgent(agentName);
        Goal goal = goalService.create(objective);
        CompletableFuture.runAsync(() -> run(goal, agent));
        return goal;
    }

    @Override
    public Goal executeSync(String agentName, String objective) {
        return executeSync(agentName, objective, null);
    }

    @Override
    public Goal executeSync(String agentName, String objective, String sessionId) {
        Agent agent = requireAgent(agentName);
        Goal goal = goalService.create(objective, sessionId);
        run(goal, agent);
        return goal;
    }

    @Override
    public Optional<Goal> getGoal(String id) {
        return goalService.get(id);
    }

    @Override
    public List<Goal> allGoals() {
        return goalService.all();
    }

    @Override
    public Set<String> agentNames() {
        return agents.keySet();
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
}