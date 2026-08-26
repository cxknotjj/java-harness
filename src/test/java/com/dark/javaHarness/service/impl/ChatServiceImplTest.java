package com.dark.javaHarness.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.domain.RouteDecision;
import com.dark.javaHarness.domain.dto.ChatRequest;
import com.dark.javaHarness.domain.dto.ChatResponse;
import com.dark.javaHarness.enums.GoalStatus;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.ChatService;
import com.dark.javaHarness.service.RouteJudge;
import com.dark.javaHarness.service.SessionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;

/**
 * ChatServiceImpl 多轮会话记忆单测：
 * - 首次不带 sessionId → newSession=true 且自动建档
 * - 后续带 sessionId → newSession=false 且不重复建档
 * - 执行成功后 user/assistant 上下文写回 session_messages
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceImplTest {

    @Mock
    private AgentService agentService;
    @Mock
    private SessionService sessionService;
    @Mock
    private RouteJudge routeJudge;

    @InjectMocks
    private ChatServiceImpl chatService;

    private Goal succeededGoal(String sessionId, String summary) {
        Goal g = new Goal("goal-1", "hi", sessionId);
        g.succeed(summary);
        return g;
    }

    @Test
    void chat_withoutSessionId_shouldCreateNewSession() {
        ChatRequest req = new ChatRequest("你好", null, null);
        when(sessionService.createSession("anonymous", "你好")).thenReturn("42");
        when(agentService.executeSync(anyString(), anyString(), anyString()))
                .thenReturn(succeededGoal("42", "你好，我是AI"));

        ChatResponse resp = chatService.chat(req);

        assertTrue(resp.newSession(), "首次不带 sessionId 应 newSession=true");
        assertEquals("42", resp.sessionId());
        verify(sessionService).createSession("anonymous", "你好");
    }

    @Test
    void chat_withSessionId_shouldReuseSession() {
        ChatRequest req = new ChatRequest("继续说", "42", null);
        when(agentService.executeSync(anyString(), anyString(), anyString()))
                .thenReturn(succeededGoal("42", "好的，继续说"));

        ChatResponse resp = chatService.chat(req);

        assertFalse(resp.newSession(), "带已有 sessionId 应 newSession=false");
        verify(sessionService, never()).createSession(anyString(), anyString());
    }

    @Test
    void chat_syncSuccess_shouldWriteBackContext() {
        ChatRequest req = new ChatRequest("帮我写首诗", "7", null);
        when(agentService.executeSync(anyString(), anyString(), anyString()))
                .thenReturn(succeededGoal("7", "风急天高猿啸哀"));

        chatService.chat(req);

        ArgumentCaptor<UserMessage> userCapture = ArgumentCaptor.forClass(UserMessage.class);
        ArgumentCaptor<AssistantMessage> assistantCapture = ArgumentCaptor.forClass(AssistantMessage.class);
        verify(sessionService).saveContext(anyString(), userCapture.capture());
        verify(sessionService).saveContext(anyString(), assistantCapture.capture());
        verify(sessionService).touchSession(eq("7"), eq("帮我写首诗"));

        assertEquals("帮我写首诗", userCapture.getValue().getText());
        assertEquals("风急天高猿啸哀", assistantCapture.getValue().getText());
    }

    @Test
    void chat_failure_shouldNotWriteBackContext() {
        ChatRequest req = new ChatRequest("hi", "1", null);
        Goal failed = new Goal("goal-2", "hi", "1");
        failed.fail("invalid_api_key");
        when(agentService.executeSync(anyString(), anyString(), anyString())).thenReturn(failed);

        ChatResponse resp = chatService.chat(req);

        assertEquals(GoalStatus.FAILED.name(), resp.status());
        verify(sessionService, never()).saveContext(anyString(), any());
    }

    @Test
    void streamReactive_emitsSseTokensAndMeta() {
        ChatRequest req = new ChatRequest("hi", null, null);
        when(sessionService.createSession("anonymous", "hi")).thenReturn("50");
        when(agentService.executeStreamReactive("general", "hi", "50"))
                .thenReturn(Flux.just("a", "b"));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        assertTrue(lines.contains("data: a"), "应包含第 1 个 token");
        assertTrue(lines.contains("data: b"), "应包含第 2 个 token");
        assertTrue(lines.contains("data: [DONE]"), "token 结束后应包含 [DONE]");
        assertTrue(lines.contains("event: meta"), "末尾应包含 meta 事件");
        String metaData = lines.stream()
                .filter(l -> l.startsWith("data: {\"sessionId"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("应包含 meta 的 data 行"));
        assertTrue(metaData.contains("\"sessionId\":\"50\""), "meta 应包含 sessionId=50");
        assertTrue(metaData.contains("\"status\":\"SUCCEEDED\""), "meta 应包含 status=SUCCEEDED");
    }

    @Test
    void streamReactive_success_shouldWriteBackContext() {
        ChatRequest req = new ChatRequest("hi", "50", null);
        when(agentService.executeStreamReactive("general", "hi", "50"))
                .thenReturn(Flux.just("a", "b"));

        chatService.streamReactive(req).collectList().block();

        ArgumentCaptor<UserMessage> userCapture = ArgumentCaptor.forClass(UserMessage.class);
        ArgumentCaptor<AssistantMessage> assistantCapture = ArgumentCaptor.forClass(AssistantMessage.class);
        verify(sessionService).saveContext(eq("50"), userCapture.capture());
        verify(sessionService).saveContext(eq("50"), assistantCapture.capture());
        verify(sessionService).touchSession(eq("50"), eq("hi"));
        assertEquals("hi", userCapture.getValue().getText(), "写回 user 应与原始提问一致");
        assertEquals("ab", assistantCapture.getValue().getText(), "写回 assistant 应为完整回复");
    }

    @Test
    void streamReactive_onError_shouldNotWriteBackContext() {
        ChatRequest req = new ChatRequest("hi", "50", null);
        when(agentService.executeStreamReactive("general", "hi", "50"))
                .thenReturn(Flux.error(new IllegalStateException("boom")));

        chatService.streamReactive(req).collectList().block();

        verify(sessionService, never()).saveContext(anyString(), any());
        verify(sessionService, never()).touchSession(anyString(), anyString());
    }

    @Test
    void streamReactive_withAgentId_shouldRouteByAgentId() {
        ChatRequest req = new ChatRequest("hi", "50", 2L);
        when(agentService.executeStreamReactiveByAgentId(2L, "hi", "50"))
                .thenReturn(Flux.just("writer-token"));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        assertTrue(lines.contains("data: writer-token"), "应按 agentId 路由到对应 Agent 的流");
        verify(agentService, never()).executeStreamReactive(anyString(), anyString(), anyString());
    }

    @Test
    void chat_shouldInvokeMainAgentRouteJudge() {
        ChatRequest req = new ChatRequest("你好", null, null);
        when(sessionService.createSession("anonymous", "你好")).thenReturn("1");
        when(agentService.executeSync(anyString(), anyString(), anyString()))
                .thenReturn(succeededGoal("1", "你好，我是AI"));
        when(routeJudge.judge("你好")).thenReturn(RouteDecision.SIMPLE);

        chatService.chat(req);

        verify(routeJudge).judge("你好");
    }

    @Test
    void streamReactive_shouldInvokeMainAgentRouteJudge() {
        ChatRequest req = new ChatRequest("调研竞品", null, null);
        when(sessionService.createSession("anonymous", "调研竞品")).thenReturn("50");
        when(agentService.executeStreamReactive("general", "调研竞品", "50"))
                .thenReturn(Flux.just("a"));
        when(routeJudge.judge("调研竞品")).thenReturn(RouteDecision.COMPLEX);

        chatService.streamReactive(req).collectList().block();

        verify(routeJudge).judge("调研竞品");
    }

    @Test
    void streamReactive_onError_emitsErrorEvent() {
        ChatRequest req = new ChatRequest("hi", "50", null);
        when(agentService.executeStreamReactive("general", "hi", "50"))
                .thenReturn(Flux.error(new IllegalStateException("boom")));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        assertTrue(lines.contains("event: error"), "出错时应包含 error 事件");
        assertTrue(lines.contains("data: boom"), "应包含错误信息");
        assertTrue(lines.contains("event: meta"), "出错后应以 meta 收尾");
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("data: {\"sessionId") && l.contains("\"status\":\"FAILED\"")),
                "出错后 meta 应为 FAILED");
    }
}