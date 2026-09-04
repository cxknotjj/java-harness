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
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.client.ChatClient.StreamResponseSpec;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import reactor.core.publisher.Flux;

/**
 * AgentChatCaller 重试行为单测：编排环节调用器在模型调用失败时按策略自动重试。
 * call() 底层统一走流式通道（可中止），stub 亦为流式（stream().content() 返回 Flux）。
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

    /** 组装一个流式 stub 客户端：content() 的 Flux 由调用方逐次给出，calls 计数每次尝试 +1 */
    private StreamStub streamStub(AtomicInteger calls, Flux<String>... contents) {
        ChatClient c = mock(ChatClient.class);
        ChatClientRequestSpec rs = mock(ChatClientRequestSpec.class);
        StreamResponseSpec ss = mock(StreamResponseSpec.class);
        when(c.prompt()).thenReturn(rs);
        when(rs.system(anyString())).thenReturn(rs);
        when(rs.user(anyString())).thenReturn(rs);
        when(rs.stream()).thenReturn(ss);
        when(ss.content()).thenAnswer(inv -> {
            calls.incrementAndGet();
            return contents[calls.get() - 1];
        });
        return new StreamStub(c, rs);
    }

    private record StreamStub(ChatClient client, ChatClientRequestSpec rs) {
    }

    /** 首次调用抛可重试错误，随后第 2 次调用成功 */
    @Test
    void call_retryAfterRetryableFailure_returnsSecondAttemptContent() {
        AtomicInteger calls = new AtomicInteger();
        // 第 1 次抛 500，第 2 次返回内容
        StreamStub stub = streamStub(calls,
                Flux.error(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR)),
                Flux.just("retried-ok"));

        String result = callerWithClient(stub.client()).call("s1", "coder", "sys", "任务");

        assertEquals(2, calls.get(), "首次失败后应重试并成功");
        assertEquals("retried-ok", result);
    }

    @Test
    void call_retryExhausted_whenAlwaysFails() {
        AtomicInteger calls = new AtomicInteger();
        StreamStub stub = streamStub(calls,
                Flux.error(new HttpServerErrorException(HttpStatus.BAD_GATEWAY)),
                Flux.error(new HttpServerErrorException(HttpStatus.BAD_GATEWAY)),
                Flux.error(new HttpServerErrorException(HttpStatus.BAD_GATEWAY)));

        assertThrows(HttpServerErrorException.class,
                () -> callerWithClient(stub.client()).call("s", "coder", "sys", "任务"));
        assertEquals(3, calls.get(), "应重试满 3 次后抛出");
    }

    @Test
    void call_nonRetryable_throwsImmediately() {
        AtomicInteger calls = new AtomicInteger();
        StreamStub stub = streamStub(calls,
                Flux.error(new org.springframework.web.client.HttpClientErrorException(HttpStatus.BAD_REQUEST)));

        assertThrows(org.springframework.web.client.HttpClientErrorException.class,
                () -> callerWithClient(stub.client()).call("s", "lead", "sys", "任务"));
        assertEquals(1, calls.get(), "400 不可重试，应只调用一次");
    }

    /** 取消不是可重试错误：执行中置位中止后即使剩余重试额度也绝不重试 */
    @Test
    void call_cancelledMidStream_neverRetried() {
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        ChatClient c = mock(ChatClient.class);
        ChatClientRequestSpec rs = mock(ChatClientRequestSpec.class);
        StreamResponseSpec ss = mock(StreamResponseSpec.class);
        when(c.prompt()).thenReturn(rs);
        when(rs.system(anyString())).thenReturn(rs);
        when(rs.user(anyString())).thenReturn(rs);
        when(rs.stream()).thenReturn(ss);
        when(ss.content()).thenAnswer(inv -> {
            calls.incrementAndGet();
            // 第 2 个 token 到达时模拟客户端断连置位
            return Flux.just("a", "b", "c").doOnNext(t -> {
                if ("b".equals(t)) {
                    cancelled.set(true);
                }
            });
        });

        assertThrows(CancellationException.class,
                () -> callerWithClient(c).call("s", "coder", "sys", "任务", null,
                        new Advisor[0], cancelled::get),
                "执行中置位应在 token 边界中止并抛取消异常");
        assertEquals(1, calls.get(), "取消中止后不得重试");
    }
}
