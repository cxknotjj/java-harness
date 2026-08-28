package com.dark.javaHarness.service.impl;

import com.dark.javaHarness.agent.Agent;
import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.enums.AgentConstants;
import com.dark.javaHarness.service.AgentConfigProvider;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.GoalService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Agent 编排服务实现：接收一个请求，路由到对应 Agent，
 * 执行目标并回写 Goal 生命周期状态（RUNNING -> SUCCEEDED/FAILED）。
 */
@Service
public class AgentServiceImpl implements AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentServiceImpl.class);

    private final GoalService goalService;
    private final AgentConfigProvider agentConfigProvider;
    private final ConcurrentMap<String, Agent> agents;

    public AgentServiceImpl(GoalService goalService,
                            AgentConfigProvider agentConfigProvider,
                            List<Agent> agentList) {
        this.goalService = goalService;
        this.agentConfigProvider = agentConfigProvider;
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

    /**
     * 响应式流式执行：返回逐 token 产出的 {@link Flux}。
     * 在 AgentService 层完成 goal 生命周期（RUNNING -> SUCCEEDED/FAILED），
     * doOnNext 收集完整 token，doOnComplete/doOnError 回写 goal 状态；
     * 阻塞的 DB 操作与 Agent 执行通过 subscribeOn(boundedElastic) 隔离，避免阻塞调用方线程。
     */
    @Override
    public Flux<String> executeStreamReactive(String agentName, String objective, String sessionId) {
        Agent agent = requireAgent(agentName);
        Goal goal = goalService.create(objective, sessionId);
        goal.markRunning();
        goalService.update(goal);
        StringBuilder full = new StringBuilder();
        return agent.executeStreamReactive(goal)
                .doOnNext(full::append)
                .doOnComplete(() -> {
                    String summary = full.toString();
                    goal.succeed(summary);
                    goalService.update(goal);
                    log.info("[{}] goal '{}' STREAMED(reactive) -> 长度={}", goal.id(), goal.objective(), summary.length());
                })
                .doOnError(e -> {
                    String reason = errorReason(e);
                    log.warn("[{}] goal '{}' FAILED(reactive): {}", goal.id(), goal.objective(), reason);
                    goal.fail(reason);
                    goalService.update(goal);
                })
                // 客户端断开（超时/退出）→ Reactor cancel：complete/error 均不触发，在此兜底回写 goal，
                // 避免状态残留 RUNNING（单次 update 直写：cancel 回调频率=断连次数，量小可同步）
                .doOnCancel(() -> {
                    log.warn("[{}] goal '{}' 取消：客户端断开，停止推送并终止编排", goal.id(), goal.objective());
                    goal.fail("客户端断开，编排已取消");
                    goalService.update(goal);
                })
                // 阻塞 DB 操作（create/markRunning/update）与 Agent 执行切到 boundedElastic，避免阻塞调用方
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 按 agentId 响应式流式执行：解析出 agentName 后路由，未命中回退默认 Agent（general）。
     */
    @Override
    public Flux<String> executeStreamReactiveByAgentId(Long agentId, String objective, String sessionId) {
        String agentName = findAgentNameById(agentId).orElse(AgentConstants.DEFAULT_AGENT);
        log.info("[agent切换] agentId={} -> agentName='{}'{}", agentId, agentName,
                agentId != null && AgentConstants.DEFAULT_AGENT.equals(agentName) ? " (未命中，回退默认)" : "");
        return executeStreamReactive(agentName, objective, sessionId);
    }

    /** 列出已注册的 Agent 名称 */
    @Override
    public Set<String> agentNames() {
        return agents.keySet();
    }

    /** 按 agentId 从 agent 表查询 agentName（委托 AgentConfigProvider，CLI 传入 agentId 时用于路由映射） */
    @Override
    public Optional<String> findAgentNameById(Long agentId) {
        return agentConfigProvider.findAgentNameById(agentId);
    }

    /** 从 agent 表读取指定 Agent 的运行配置（模型 + 系统提示词，委托 AgentConfigProvider） */
    @Override
    public Optional<AgentConfig> getAgentConfig(String agentName) {
        return agentConfigProvider.getAgentConfig(agentName);
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
    private String errorReason(Throwable e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.toString() : e.getMessage();
    }
}