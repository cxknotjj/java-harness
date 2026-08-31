package com.dark.javaHarness.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.RouteDecision;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * LlmRouteJudge 单测：
 * - 合法 JSON 返回 complex / simple 时判定正确
 * - 非 JSON / 空 / 调用异常时兜底 SIMPLE（TODO ⑤ 宁可简单）
 */
@ExtendWith(MockitoExtension.class)
class LlmRouteJudgeTest {

    @Mock
    private ChatClientRegistry clientRegistry;
    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClientRequestSpec requestSpec;
    @Mock
    private CallResponseSpec responseSpec;

    private LlmRouteJudge judge;

    /** 组装：registry.get(route-judge) 返回 mock client，prompt 链式 stub 到 chatResponse() */
    private void stubContent(String content) {
        when(clientRegistry.get(anyString())).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any(org.springframework.ai.openai.OpenAiChatOptions.class)))
                .thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.chatResponse()).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(content)))));
        judge = new LlmRouteJudge(clientRegistry, null);
    }

    @Test
    void judge_whenLlmReturnsComplex_shouldReturnComplex() {
        stubContent("{\"route\":\"complex\"}");
        assertEquals(RouteDecision.COMPLEX, judge.judge("调研竞品并输出一份报告"));
    }

    @Test
    void judge_whenLlmReturnsSimple_shouldReturnSimple() {
        stubContent("{\"route\":\"simple\"}");
        assertEquals(RouteDecision.SIMPLE, judge.judge("你好"));
    }

    @Test
    void judge_whenLlmReturnsInvalidJson_shouldFallbackSimple() {
        stubContent("这不是合法的 JSON");
        assertEquals(RouteDecision.SIMPLE, judge.judge("你好"));
    }

    @Test
    void judge_whenLlmReturnsBlank_shouldFallbackSimple() {
        stubContent("");
        assertEquals(RouteDecision.SIMPLE, judge.judge("你好"));
    }

    @Test
    void judge_whenCallThrows_shouldFallbackSimple() {
        when(clientRegistry.get(anyString())).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any(org.springframework.ai.openai.OpenAiChatOptions.class)))
                .thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new IllegalStateException("llm down"));
        judge = new LlmRouteJudge(clientRegistry, null);

        assertEquals(RouteDecision.SIMPLE, judge.judge("你好"), "调用异常应兜底 SIMPLE 而不抛出");
    }

    @Test
    void judge_whenMessageBlank_shouldReturnSimpleWithoutCall() {
        judge = new LlmRouteJudge(clientRegistry, null);
        assertEquals(RouteDecision.SIMPLE, judge.judge("  "));
        assertEquals(RouteDecision.SIMPLE, judge.judge(null));
    }
}