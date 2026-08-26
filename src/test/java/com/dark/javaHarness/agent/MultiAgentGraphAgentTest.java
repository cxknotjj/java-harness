package com.dark.javaHarness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.service.AgentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;

/**
 * MultiAgentGraphAgent 单测：
 * - 能构建 StateGraph 并执行（lead 拆解 → 并行子任务 → 聚合）
 * - lead 返回合法 JSON 时，execute() 返回最终回答（非空）
 * - 节点层复用 ChatClient（mock 固定 content）
 */
@ExtendWith(MockitoExtension.class)
class MultiAgentGraphAgentTest {

    @Mock
    private ChatClientRegistry clientRegistry;
    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClientRequestSpec requestSpec;
    @Mock
    private CallResponseSpec responseSpec;
    @Mock
    private AgentService agentService;

    private MultiAgentGraphAgent agent;

    /** lead 返回两条子任务，子任务/聚合返回固定文本 */
    private String fixedContent() {
        return "{\"subtasks\":[\"子任务A\",\"子任务B\"]}";
    }

    private void stubChat(String content) {
        when(clientRegistry.get(any())).thenReturn(chatClient);
        when(agentService.getAgentConfig(anyString())).thenReturn(java.util.Optional.empty());
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(content);
    }

    @Test
    void execute_shouldBuildGraphAndReturnFinalAnswer() {
        stubChat(fixedContent());
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService);

        Goal goal = new Goal("g1", "调研竞品并输出报告");
        String reply = agent.execute(goal);

        assertNotNull(reply);
        assertFalse(reply.isBlank(), "execute 应产出最终回答");
    }

    @Test
    void execute_returnsNonEmptyForAggregate() {
        // 所有节点统一返回固定 content；lead 解析出 subtasks，聚合把该 content 作为最终回答
        stubChat(fixedContent());
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService);

        String reply = agent.execute(new Goal("g2", "hello"));

        assertNotNull(reply);
        assertEquals(fixedContent(), reply, "聚合节点把读取到的结果作为最终回答返回");
    }
}