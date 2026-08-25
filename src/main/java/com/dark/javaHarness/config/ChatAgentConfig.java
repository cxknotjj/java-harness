package com.dark.javaHarness.config;

import com.dark.javaHarness.agent.GeneralAssistantAgent;
import com.dark.javaHarness.agent.GraphAssistantAgent;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.SessionService;
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
 * general 采用 GraphAssistantAgent（Spring AI Alibaba Graph 状态编排承载执行层），
 * 其余仍为 GeneralAssistantAgent，便于对比 Graph 路径是否生效。
 */
@Configuration
public class ChatAgentConfig {

    @Bean
    public GraphAssistantAgent generalAgent(ChatClientRegistry registry,
                                            SessionService memoryStore,
                                            @Lazy AgentService agentService,
                                            DataSource dataSource) {
        return new GraphAssistantAgent("general", registry, memoryStore, agentService, dataSource);
    }

    @Bean
    public GeneralAssistantAgent writerAgent(ChatClientRegistry registry,
                                             SessionService memoryStore,
                                             @Lazy AgentService agentService) {
        return new GeneralAssistantAgent("writer", registry, memoryStore, agentService);
    }

    @Bean
    public GeneralAssistantAgent coderAgent(ChatClientRegistry registry,
                                            SessionService memoryStore,
                                            @Lazy AgentService agentService) {
        return new GeneralAssistantAgent("coder", registry, memoryStore, agentService);
    }

    @Bean
    public GeneralAssistantAgent deepseekAgent(ChatClientRegistry registry,
                                               SessionService memoryStore,
                                               @Lazy AgentService agentService) {
        return new GeneralAssistantAgent("deepseek", registry, memoryStore, agentService);
    }
}