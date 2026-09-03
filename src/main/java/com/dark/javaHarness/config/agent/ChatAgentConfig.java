package com.dark.javaHarness.config.agent;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.dark.javaHarness.agent.GeneralAssistantAgent;
import com.dark.javaHarness.agent.MultiAgentGraphAgent;
import com.dark.javaHarness.enums.AgentConstants;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.service.impl.LlmCallRecorder;
import com.dark.javaHarness.tool.ToolAssignments;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * 多 Agent 装配：注册多个 Agent 实例，每个仅绑定一个 agentName。
 *
 * ChatClient 不在此处手工构建，统一由 ChatClientRegistry 管理（Registry 模式）：
 * Agent 运行时按 agent 表 model 字段从注册表取对应厂商的 ChatClient，
 * 模型接入与 Agent 行为彻底解耦。新增 Agent = 新增一个 bean + agent 表一行。
 *
 * 全部 Agent 统一采用 GeneralAssistantAgent（直接单次调用大模型的简单路径）。
 * 多 Agent 任务编排链路（Spring-AI Graph）由 multiAgent bean 承载，供复杂请求（COMPLEX）使用。
 * 所有 Agent 共享 LlmCallRecorder：每次 LLM 调用的耗时/token 异步落 llm_call_log（观测层）。
 */
@Configuration
public class ChatAgentConfig {

    @Bean
    public GeneralAssistantAgent generalAgent(ChatClientRegistry registry,
                                              SessionService memoryStore,
                                              @Lazy AgentService agentService,
                                              ToolAssignments toolAssignments,
                                              LlmCallRecorder recorder,
                                              com.dark.javaHarness.config.ContextBudgetProperties budgets) {
        return new GeneralAssistantAgent("general", registry, memoryStore, agentService,
                toolAssignments, recorder, budgets);
    }

    @Bean
    public GeneralAssistantAgent deepseekAgent(ChatClientRegistry registry,
                                               SessionService memoryStore,
                                               @Lazy AgentService agentService,
                                               ToolAssignments toolAssignments,
                                               LlmCallRecorder recorder,
                                               com.dark.javaHarness.config.ContextBudgetProperties budgets) {
        return new GeneralAssistantAgent("deepseek", registry, memoryStore, agentService,
                toolAssignments, recorder, budgets);
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

    /** 复杂路径执行体：多 Agent 编排（lead 拆解 → 并行子任务 → 聚合），带 MySQL 检查点与静态 prompt 预算。 */
    @Bean
    public MultiAgentGraphAgent multiAgent(ChatClientRegistry registry,
                                           @Lazy AgentService agentService,
                                           ToolAssignments toolAssignments,
                                           LlmCallRecorder recorder,
                                           BaseCheckpointSaver graphCheckpointSaver,
                                           com.dark.javaHarness.config.ContextBudgetProperties budgets) {
        return new MultiAgentGraphAgent(AgentConstants.MULTI_AGENT, registry, agentService,
                toolAssignments, recorder, graphCheckpointSaver, budgets);
    }
}
