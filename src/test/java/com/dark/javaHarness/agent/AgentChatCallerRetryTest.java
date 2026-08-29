package com.dark.javaHarness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.tool.ToolAssignments;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

/**
 * AgentChatCaller 重试行为单测：编排环节调用器在模型调用失败时按策略自动重试。
 */
class AgentChatCallerRetryTest {

    private ChatClientRegistry clientRegistry;
    private AgentService agentService;
    private ToolAssignments toolAssignments;

    @BeforeEach
    void setUp() {
        clientRegistry = mock(ChatClientRegistry.class);
        agentService = mock(AgentService.class);
        toolAssignments = mock(ToolAssignments.class);
        when(agentService.getAgentConfig(anyString())).thenReturn(java.util.Optional.empty());
        when(toolAssignments.forAgent(any())).thenReturn(ToolAssignments.ToolSet.EMPTY);
    }

    private AgentChatCaller callerWithClient(ChatClient client) {
        when(clientRegistry.get(any())).thenReturn(client);
        // 基准退避调小加速，确保测试不慢
        return new AgentChatCaller(clientRegistry, agentService, toolAssignments, null,
                new LlmRetry(3, 1));
    }

    /** 首次调用抛可重试错误，随后第 2 次调用成功 */
    @Test
    void call_retryAfterRetryableFailure_returnsSecondAttemptContent() {
        ChatClient c = mock(ChatClient.class);
        ChatClientRequestSpec rs = mock(ChatClientRequestSpec.class);
        CallResponseSpec cs = mock(CallResponseSpec.class);
        AtomicInteger calls = new AtomicInteger();
        when(c.prompt()).thenReturn(rs);
        when(rs.system(anyString())).thenReturn(rs);
        when(rs.user(anyString())).thenReturn(rs);
        when(rs.call()).thenReturn(cs);
        // 第 1 次抛 500，第 2 次返回内容
        when(cs.chatResponse()).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                throw new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR);
            }
            return chatResponseOf("retried-ok");
        });

        String result = callerWithClient(c).call("s1", "coder", "sys", "任务");

        assertEquals(2, calls.get(), "首次失败后应重试并成功");
        assertEquals("retried-ok", result);
    }

    @Test
    void call_retryExhausted_whenAlwaysFails() {
        ChatClient c = mock(ChatClient.class);
        ChatClientRequestSpec rs = mock(ChatClientRequestSpec.class);
        CallResponseSpec cs = mock(CallResponseSpec.class);
        AtomicInteger calls = new AtomicInteger();
        when(c.prompt()).thenReturn(rs);
        when(rs.system(anyString())).thenReturn(rs);
        when(rs.user(anyString())).thenReturn(rs);
        when(rs.call()).thenReturn(cs);
        when(cs.chatResponse()).thenAnswer(inv -> {
            calls.incrementAndGet();
            throw new HttpServerErrorException(HttpStatus.BAD_GATEWAY);
        });

        assertThrows(HttpServerErrorException.class,
                () -> callerWithClient(c).call("s", "coder", "sys", "任务"));
        assertEquals(3, calls.get(), "应重试满 3 次后抛出");
    }

    @Test
    void call_nonRetryable_throwsImmediately() {
        ChatClient c = mock(ChatClient.class);
        ChatClientRequestSpec rs = mock(ChatClientRequestSpec.class);
        CallResponseSpec cs = mock(CallResponseSpec.class);
        AtomicInteger calls = new AtomicInteger();
        when(c.prompt()).thenReturn(rs);
        when(rs.system(anyString())).thenReturn(rs);
        when(rs.user(anyString())).thenReturn(rs);
        when(rs.call()).thenReturn(cs);
        when(cs.chatResponse()).thenAnswer(inv -> {
            calls.incrementAndGet();
            throw new org.springframework.web.client.HttpClientErrorException(
                    HttpStatus.BAD_REQUEST);
        });

        assertThrows(org.springframework.web.client.HttpClientErrorException.class,
                () -> callerWithClient(c).call("s", "lead", "sys", "任务"));
        assertEquals(1, calls.get(), "400 不可重试，应只调用一次");
    }

    private static org.springframework.ai.chat.model.ChatResponse chatResponseOf(String content) {
        return new org.springframework.ai.chat.model.ChatResponse(List.of(
                new org.springframework.ai.chat.model.Generation(
                        new org.springframework.ai.chat.messages.AssistantMessage(content))));
    }
}