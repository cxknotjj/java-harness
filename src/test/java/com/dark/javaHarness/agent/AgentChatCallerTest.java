package com.dark.javaHarness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.service.AgentService;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

/**
 * AgentChatCaller 单测：
 * - 未知工具幻觉容错：模型发起不存在的工具调用（No ToolCallback found）→ 去掉工具列表重试一次
 * - 其他异常照常抛出（交由上层重试/降级）
 * - disableTools=true 时不注入任何工具（request 级 toolCallbacks/tools 均不调用）
 * - 取消令牌（客户端断连防 token 浪费）：call 底层走流式通道——
 *   执行中置位在 token 边界中止且不重试；置位后调用直接抛取消异常（零 HTTP 请求）
 */
@ExtendWith(MockitoExtension.class)
class AgentChatCallerTest {

    /** 文本 token → ChatResponse 流（生产流式链已切 chatResponse 通道以捕获 streamUsage 末帧） */
    private static Flux<ChatResponse> fluxOf(String... tokens) {
        return Flux.fromArray(List.of(tokens).stream()
                .map(t -> new ChatResponse(List.of(new Generation(new AssistantMessage(t)))))
                .toArray(ChatResponse[]::new));
    }


    @Mock
    private ChatClientRegistry clientRegistry;
    @Mock
    private AgentService agentService;
    @Mock
    private ChatClient client;
    @Mock
    private ChatClient.ChatClientRequestSpec spec;
    @Mock
    private ChatClient.StreamResponseSpec streamSpec;

    private AgentChatCaller caller;

    @BeforeEach
    void setUp() {
        caller = new AgentChatCaller(clientRegistry, agentService, null, null);
        // lenient：invokeOnce_disableTools 用例直接调 invokeAndRecord，不查表
        org.mockito.Mockito.lenient().when(agentService.getAgentConfig("researcher"))
                .thenReturn(Optional.of(new AgentConfig(1L, "m1", "系统提示词")));
        when(clientRegistry.get(1L)).thenReturn(client);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.options(any(OpenAiChatOptions.class))).thenReturn(spec);
        // call() 底层统一走流式通道收集完整内容（阻塞调用不可中断，流式是唯一可中止通道）
        when(spec.stream()).thenReturn(streamSpec);
    }

    @Test
    void call_unknownToolHallucination_retriesOnceWithoutTools() {
        when(streamSpec.chatResponse())
                .thenReturn(Flux.error(new IllegalStateException("No ToolCallback found for tool name: researcher")))
                .thenReturn(fluxOf("调研完成：4399 公司……"));

        String out = caller.call("s1", "researcher", "兜底提示", "任务内容", null);

        assertEquals("调研完成：4399 公司……", out, "幻觉工具调用应降级为无工具重试并返回文本结果");
        // 两次调用（原样 + 去工具）
        verify(spec, never()).toolCallbacks(any(org.springframework.ai.tool.ToolCallback[].class));
    }

    @Test
    void call_otherError_propagates() {
        when(streamSpec.chatResponse()).thenReturn(Flux.error(new IllegalStateException("无关异常")));

        assertThrows(IllegalStateException.class,
                () -> caller.call("s1", "researcher", "兜底提示", "任务内容", null));
    }

    @Test
    void invokeOnce_disableTools_skipsAllToolInjection() {
        when(streamSpec.chatResponse()).thenReturn(fluxOf("ok"));

        String out = caller.invokeAndRecord(
                new AgentConfig(1L, "m1", "系统提示词"), "s1", "researcher",
                "兜底提示", "任务内容", null, true, "m1", System.currentTimeMillis());

        assertEquals("ok", out);
        verify(spec, never()).toolCallbacks(any(org.springframework.ai.tool.ToolCallback[].class));
        verify(spec, never()).tools(any(Object[].class));
    }

    @Test
    void call_collectsAllTokensFromStreamChannel() {
        // call() 改为流式背书：全部 token 收集后拼接返回
        when(streamSpec.chatResponse()).thenReturn(fluxOf("你好", "，", "世界"));

        String out = caller.call("s1", "researcher", "兜底提示", "任务内容", null);

        assertEquals("你好，世界", out);
    }

    /** 回归（streamUsage）：末帧只含 usage（无 generations，contentOf=null）应跳过而非炸流 */
    @Test
    void call_usageOnlyFinalFrame_skippedWithoutError() {
        when(streamSpec.chatResponse()).thenReturn(Flux.concat(
                fluxOf("部分", "回答"),
                Flux.just(new ChatResponse(List.of()))));

        String out = caller.call("s1", "researcher", "兜底提示", "任务内容", null);

        assertEquals("部分回答", out, "usage 空帧应被跳过，内容完整");
    }

    @Test
    void call_cancelledMidStream_abortsAtTokenBoundary_noRetry() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        // 第 2 个 token 到达时模拟客户端断连置位：takeUntil 放行的终止前元素在 doOnNext 拦截中止
        when(streamSpec.chatResponse()).thenReturn(fluxOf("a", "b", "c")
                .doOnNext(resp -> {
                    if ("b".equals(AgentChatCaller.contentOf(resp))) {
                        cancelled.set(true);
                    }
                }));

        assertThrows(CancellationException.class,
                () -> caller.call("s1", "researcher", "兜底提示", "任务内容", null, new Advisor[0], cancelled::get),
                "执行中置位应在 token 边界中止并抛取消异常");

        // 取消不是可重试错误：仅一次流式尝试（prompt 只被组装一次）
        verify(client, times(1)).prompt();
    }

    @Test
    @org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
    void call_cancelledBeforeInvocation_throwsWithoutHttpCall() {
        AtomicBoolean cancelled = new AtomicBoolean(true);

        assertThrows(CancellationException.class,
                () -> caller.call("s1", "researcher", "兜底提示", "任务内容", null, new Advisor[0], cancelled::get),
                "置位后调用应直接抛取消异常");

        // 零 HTTP 请求：未触达客户端
        verify(client, never()).prompt();
    }

    @Test
    @org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
    void stream_cancelledBeforeInvocation_throwsWithoutHttpCall() {
        AtomicBoolean cancelled = new AtomicBoolean(true);

        assertThrows(CancellationException.class,
                () -> caller.stream("s1", "researcher", "兜底提示", "任务内容",
                        token -> { }, null, new Advisor[0], cancelled::get),
                "置位后流式调用应直接抛取消异常");

        verify(client, never()).prompt();
    }
}
