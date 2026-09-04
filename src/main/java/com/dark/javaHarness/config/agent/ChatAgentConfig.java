package com.dark.javaHarness.config.agent;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.dark.javaHarness.agent.AgentRegistry;
import com.dark.javaHarness.agent.MultiAgentGraphAgent;
import com.dark.javaHarness.enums.AgentConstants;
import com.dark.javaHarness.service.AgentConfigProvider;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.service.impl.LlmCallRecorder;
import com.dark.javaHarness.tool.ToolAssignments;
import com.dark.javaHarness.tool.ToolLazyManager;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * 多 Agent 装配：对话 Agent 由 agent 表驱动自动注册（AgentRegistry 承载），
 * 新增 Agent = agent 表一行（is_internal=0 即对话 Agent，内部角色行置 1，免改代码）。
 *
 * ChatClient 不在此处手工构建，统一由 ChatClientRegistry 管理（Registry 模式）：
 * Agent 运行时按 agent 表 model 字段从注册表取对应厂商的 ChatClient，
 * 模型接入与 Agent 行为彻底解耦。
 *
 * 对话 Agent 统一采用 GeneralAssistantAgent（直接单次调用大模型的简单路径），
 * 实例由 AgentRegistry 按表行构造，不再逐个注册 bean。多 Agent 任务编排链路
 * （Spring-AI Graph）由 multiAgent bean 承载，供复杂请求（COMPLEX）使用，
 * 经 AgentRegistry 预注入路由。
 * 所有 Agent 共享 LlmCallRecorder：每次 LLM 调用的耗时/token 异步落 llm_call_log（观测层）。
 * 所有 Agent 共享 ToolLazyManager：会话级工具展开集跨路径（A/B）通用——该 bean 只依赖
 * ToolAssignments（纯工具面数据），与 AgentService/Agent bean 无依赖关系，无循环依赖。
 */
@Configuration
public class ChatAgentConfig {

    /**
     * 工具 Schema 延迟加载管理器（app.prompt.lazy-tools.enabled，默认 true）：
     * 路径 A 与编排路径 B 共用同一实例，同一 sessionId 的展开集跨路径通用。
     */
    @Bean
    public ToolLazyManager toolLazyManager(ToolAssignments toolAssignments,
                                           @Value("${app.prompt.lazy-tools.enabled:true}") boolean lazyToolsEnabled) {
        return new ToolLazyManager(toolAssignments, lazyToolsEnabled);
    }

    /**
     * Agent 表驱动注册表：对话 Agent 路由表的唯一来源。
     * 装配顺序在方法体内显式编排（不用 initMethod，顺序最清晰）：
     * 先预注入 multi-agent 编排 bean（表行不构造编排实例），再启动注册
     * （agent 表全部 is_internal=0 行逐行容错注册 + general 行缺失时代码兜底）。
     */
    @Bean
    public AgentRegistry agentRegistry(AgentConfigProvider agentConfigProvider,
                                       ObjectProvider<AgentService> agentService,
                                       ChatClientRegistry registry,
                                       SessionService memoryStore,
                                       ToolAssignments toolAssignments,
                                       LlmCallRecorder recorder,
                                       com.dark.javaHarness.config.ContextBudgetProperties budgets,
                                       ToolLazyManager toolLazyManager,
                                       MultiAgentGraphAgent multiAgent) {
        AgentRegistry agentRegistry = new AgentRegistry(agentConfigProvider, agentService, registry,
                memoryStore, toolAssignments, recorder, budgets, toolLazyManager);
        agentRegistry.register(multiAgent);
        agentRegistry.init();
        return agentRegistry;
    }

    /**
     * 复杂路径检查点存储器：graph-core MysqlSaver，落库到 harness 库的
     * GRAPH_THREAD / GRAPH_CHECKPOINT 两表（首次自动建表，CREATE_IF_NOT_EXISTS，
     * 与 Flyway 管理的表互不冲突）。threadId=goalId，每个 superstep 落一条，
     * 供长编排断开/失败后断点续跑。
     */
    @Bean
    public BaseCheckpointSaver graphCheckpointSaver(DataSource dataSource) {
        return MysqlSaver.builder()
                .dataSource(dataSource)
                .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
                .build();
    }

    /** 复杂路径执行体：多 Agent 编排（lead 拆解 → 并行子任务 → 聚合），带 MySQL 检查点与静态 prompt 预算；memoryStore 与 GeneralAssistantAgent 同源，lead 拆解据此注入会话记忆；toolLazyManager 与路径 A 共享（会话展开集跨路径通用） */
    @Bean
    public MultiAgentGraphAgent multiAgent(ChatClientRegistry registry,
                                           @Lazy AgentService agentService,
                                           SessionService memoryStore,
                                           ToolAssignments toolAssignments,
                                           LlmCallRecorder recorder,
                                           BaseCheckpointSaver graphCheckpointSaver,
                                           com.dark.javaHarness.config.ContextBudgetProperties budgets,
                                           ToolLazyManager toolLazyManager) {
        return new MultiAgentGraphAgent(AgentConstants.MULTI_AGENT, registry, agentService,
                toolAssignments, recorder, graphCheckpointSaver, budgets, memoryStore, toolLazyManager);
    }
}
