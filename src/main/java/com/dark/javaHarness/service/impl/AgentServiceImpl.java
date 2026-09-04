package com.dark.javaHarness.service.impl;

import com.dark.javaHarness.agent.Agent;
import com.dark.javaHarness.agent.AgentRegistry;
import com.dark.javaHarness.agent.ProgressLine;
import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.enums.AgentConstants;
import com.dark.javaHarness.service.AgentConfigProvider;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.GoalService;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Agent 编排服务实现：接收一个请求，路由到对应 Agent，
 * 执行目标并回写 Goal 生命周期状态（RUNNING -> SUCCEEDED/FAILED）。
 *
 * <p>路由表由 {@link AgentRegistry} 表驱动维护（agent 表 is_internal=0 行自动注册 +
 * multi-agent 编排 bean 预注入），本类仅做路由委托，不再自建 bean 列表路由表。
 */
@Service
public class AgentServiceImpl implements AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentServiceImpl.class);

    private final GoalService goalService;
    private final AgentConfigProvider agentConfigProvider;
    private final AgentRegistry agentRegistry;

    public AgentServiceImpl(GoalService goalService,
                            AgentConfigProvider agentConfigProvider,
                            @Lazy AgentRegistry agentRegistry) {
        this.goalService = goalService;
        this.agentConfigProvider = agentConfigProvider;
        // @Lazy 代理断环：AgentRegistry bean 创建期 init() 会经 ObjectProvider 现取本 bean，
        // 双方在创建期互等，代理注入推迟解析到首次路由调用（届时 Registry 已装配完成）
        this.agentRegistry = agentRegistry;
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
     * 在 AgentService 层完成 goal 生命周期（见 {@link #streamWithLifecycle}），
     * 新建 goal 后路由到指定 Agent 执行。
     */
    @Override
    public Flux<String> executeStreamReactive(String agentName, String objective, String sessionId) {
        Agent agent = requireAgent(agentName);
        Goal goal = goalService.create(objective, sessionId);
        Flux<String> stream = streamWithLifecycle(goal, agent);
        // 仅编排路径在流首下发 goal 进度行：goalId 尽早到达 CLI（客户端断开前也能记录，/resume 免记 ID）
        return AgentConstants.MULTI_AGENT.equals(agentName)
                ? withGoalProgress(goal, stream) : stream;
    }

    /** 复杂编排断点续跑：固定路由 multi-agent，复用既有 goal（检查点 threadId=goalId）。 */
    @Override
    public Flux<String> resumeStreamReactive(Goal goal) {
        Agent agent = requireAgent(AgentConstants.MULTI_AGENT);
        log.info("[resume] goal '{}' 断点续跑（检查点 threadId={}）", goal.id(), goal.id());
        return withGoalProgress(goal, streamWithLifecycle(goal, agent));
    }

    /** 流首插入 goal 进度行（stage=goal, detail=goalId），后续编排进度照常 */
    private static Flux<String> withGoalProgress(Goal goal, Flux<String> stream) {
        return Flux.concat(Flux.just(ProgressLine.encode("goal", goal.id())), stream);
    }

    /**
     * 流式执行 + goal 生命周期回写（RUNNING -> SUCCEEDED/FAILED/取消 FAILED）。
     * doOnNext 收集完整 token，doOnComplete/doOnError 回写 goal 状态；
     * 阻塞的 DB 操作与 Agent 执行通过 subscribeOn(boundedElastic) 隔离，避免阻塞调用方线程。
     */
    private Flux<String> streamWithLifecycle(Goal goal, Agent agent) {
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

    /** 列出已注册的 Agent 名称（委托 AgentRegistry 动态路由表） */
    @Override
    public Set<String> agentNames() {
        return agentRegistry.agentNames();
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

    /** 按名称查找 Agent（委托 AgentRegistry 表驱动路由表，未命中走惰性热注册），不存在则抛出异常 */
    private Agent requireAgent(String agentName) {
        return agentRegistry.require(agentName);
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