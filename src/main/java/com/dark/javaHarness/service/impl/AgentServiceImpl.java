package com.dark.javaHarness.service.impl;

import com.dark.javaHarness.agent.Agent;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.GoalService;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
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

    /** 提交目标给指定 Agent 异步执行（立即返回，后台执行） */
    @Override
    public Goal submit(String agentName, String objective) {
        Agent agent = requireAgent(agentName);
        Goal goal = goalService.create(objective);
        CompletableFuture.runAsync(() -> run(goal, agent));
        return goal;
    }

    /** 同步执行（无会话记忆）：阻塞直至完成并返回完整结果 */
    @Override
    public Goal executeSync(String agentName, String objective) {
        return executeSync(agentName, objective, null);
    }

    /** 同步执行（带会话记忆）：阻塞直至完成并返回完整结果 */
    @Override
    public Goal executeSync(String agentName, String objective, String sessionId) {
        Agent agent = requireAgent(agentName);
        Goal goal = goalService.create(objective, sessionId);
        run(goal, agent);
        return goal;
    }

    /** 流式执行（带会话记忆）：逐 token 回调 onToken，阻塞直至整个流结束 */
    @Override
    public Goal executeStream(String agentName, String objective, String sessionId, Consumer<String> onToken) {
        Agent agent = requireAgent(agentName);
        Goal goal = goalService.create(objective, sessionId);
        goal.markRunning();
        goalService.update(goal);
        try {
            String summary = agent.executeStream(goal, onToken);
            goal.succeed(summary);
            goalService.update(goal);
            log.info("[{}] goal '{}' STREAMED -> 长度={}", goal.id(), goal.objective(), summary == null ? 0 : summary.length());
        } catch (Exception e) {
            String reason = errorReason(e);
            log.warn("[{}] goal '{}' FAILED: {}", goal.id(), goal.objective(), reason);
            goal.fail(reason);
            goalService.update(goal);
        }
        return goal;
    }

    /** 列出已注册的 Agent 名称 */
    @Override
    public Set<String> agentNames() {
        return agents.keySet();
    }

    /** 按名称查找 Agent，不存在则抛出异常 */
    private Agent requireAgent(String agentName) {
        Agent agent = agents.get(agentName);
        if (agent == null) {
            throw new IllegalArgumentException("未知 Agent: " + agentName + "，可用: " + agents.keySet());
        }
        return agent;
    }

    /** 同步执行目标并更新其生命周期状态（RUNNING -> SUCCEEDED/FAILED） */
    private void run(Goal goal, Agent agent) {
        goal.markRunning();
        goalService.update(goal);
        try {
            String summary = agent.execute(goal);
            goal.succeed(summary);
            goalService.update(goal);
            log.info("[{}] goal '{}' SUCCEEDED -> {}", goal.id(), goal.objective(), summary);
        } catch (Exception e) {
            // 目标失败是正常业务结果（如未配置 API key 时的 401），只记一行摘要避免刷屏
            String reason = errorReason(e);
            log.warn("[{}] goal '{}' FAILED: {}", goal.id(), goal.objective(), reason);
            goal.fail(reason);
            goalService.update(goal);
        }
    }

    /** 取异常可读原因：getMessage 为空时回退到 toString，避免失败摘要为 null */
    private String errorReason(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.toString() : e.getMessage();
    }
}