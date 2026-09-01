package com.dark.javaHarness.service;

import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.domain.Goal;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import reactor.core.publisher.Flux;

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
     * 创建一个目标并响应式流式执行：返回一个逐 token 产出的 {@link Flux}。
     * Flux 订阅后异步执行（内部切到 boundedElastic 隔离阻塞 DB 与 Agent 执行），
     * 完成后回写 goal 为 SUCCEEDED，出错时回写为 FAILED。
     */
    Flux<String> executeStreamReactive(String agentName, String objective, String sessionId);

    /**
     * 按 agentId 响应式流式执行：先解析出对应 agentName 再路由到该 Agent。
     * agentId 为空或未命中时回退到默认 Agent（general）。
     */
    Flux<String> executeStreamReactiveByAgentId(Long agentId, String objective, String sessionId);

    /**
     * 复杂编排断点续跑：复用既有 goal（id 即检查点 threadId），从上次检查点继续执行。
     * 固定路由到 multi-agent；goal 状态先置回 RUNNING，成功/失败/断连照常回写。
     * 输出语义与 {@link #executeStreamReactive} 完全一致。
     */
    Flux<String> resumeStreamReactive(Goal goal);

    Set<String> agentNames();

    /**
     * 按 agentId 从 agent 表查询 agentName（用于将 CLI 传入的 agentId 映射为路由名）。
     * 查不到或查询失败返回 empty。
     */
    Optional<String> findAgentNameById(Long agentId);

    /**
     * 从 agent 表读取指定 Agent 的运行配置（模型 + 系统提示词）。
     * 无记录或读取失败返回 empty。
     */
    Optional<AgentConfig> getAgentConfig(String agentName);
}