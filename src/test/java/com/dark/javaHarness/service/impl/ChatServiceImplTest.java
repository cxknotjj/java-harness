package com.dark.javaHarness.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.agent.ProgressLine;
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

        assertTrue(lines.contains("event: token\ndata: a"), "应包含第 1 个 token（显式 event 声明）");
        assertTrue(lines.contains("event: token\ndata: b"), "应包含第 2 个 token（显式 event 声明）");
        assertTrue(lines.contains("event: token\ndata: [DONE]"), "token 结束后应包含 [DONE]（同块结构）");
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("event: meta")), "末尾应包含 meta 事件");
        String metaData = lines.stream()
                .filter(l -> l.contains("\"sessionId\":\"50\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("应包含 meta 的事件块"));
        assertTrue(metaData.startsWith("event: meta"), "meta 的 event 与 data 应在同一元素内");
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

        assertTrue(lines.contains("event: token\ndata: writer-token"), "应按 agentId 路由到对应 Agent 的流");
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
        when(agentService.executeStreamReactive("multi-agent", "调研竞品", "50"))
                .thenReturn(Flux.just("a"));
        when(routeJudge.judge("调研竞品")).thenReturn(RouteDecision.COMPLEX);

        chatService.streamReactive(req).collectList().block();

        verify(routeJudge).judge("调研竞品");
        verify(agentService).executeStreamReactive(eq("multi-agent"), eq("调研竞品"), eq("50"));
    }

    @Test
    void streamReactive_onError_emitsErrorEvent() {
        ChatRequest req = new ChatRequest("hi", "50", null);
        when(agentService.executeStreamReactive("general", "hi", "50"))
                .thenReturn(Flux.error(new IllegalStateException("boom")));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        assertTrue(lines.stream().anyMatch(l -> l.startsWith("event: error")), "出错时应包含 error 事件");
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("event: error") && l.contains("data: boom")),
                "error 的 event 与 data 应在同一元素内且包含错误信息");
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("event: meta")), "出错后应以 meta 收尾");
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"status\":\"FAILED\"")),
                "出错后 meta 应为 FAILED");
    }

    /** 复杂多 Agent 流的「进度行」应转成 event:progress + data JSON，且不作为内容 token 输出 */
    @Test
    void streamReactive_shouldMapProgressRowToProgressEvent() {
        ChatRequest req = new ChatRequest("调研竞品", null, null);
        when(sessionService.createSession("anonymous", "调研竞品")).thenReturn("50");
        // 一条进度行（MARK 前缀 + stage\u0001detail）+ 一条内容行
        String progressRow = ProgressLine.encode("拆解", "2 个子任务已就绪");
        when(agentService.executeStreamReactive("multi-agent", "调研竞品", "50"))
                .thenReturn(Flux.just(progressRow, "最终回答A"));
        when(routeJudge.judge("调研竞品")).thenReturn(RouteDecision.COMPLEX);

        List<String> lines = chatService.streamReactive(req).collectList().block();

        assertTrue(lines.stream().anyMatch(l -> l.startsWith("event: progress")), "应包含 event: progress");
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("event: progress") && l.contains("\"stage\":\"拆解\"")),
                "进度行的 event 与 data 应在同一元素内并带 stage JSON");
        assertTrue(lines.contains("event: token\ndata: 最终回答A"),
                "内容行应按 token 事件输出（progress 之后必须显式声明 event: token，否则 SSE 粘滞会吞掉 token）");
        assertFalse(lines.stream().anyMatch(l -> l.contains("{\"stage\"") == false && l.contains("子任务已就绪")),
                "进度行不应以内容 token 形式泄漏");
    }

    /**
     * 内容行含换行时必须转义为单条 SSE data 行：
     * 裸 \n 会把一条 data 断成多个物理行，CLI 按行解析只认前缀行，断行后半段被静默丢弃（结果不全）。
     */
    @Test
    void streamReactive_contentRowWithLineBreaks_shouldBeEscapedInSingleDataLine() {
        ChatRequest req = new ChatRequest("hi", "50", null);
        when(agentService.executeStreamReactive("general", "hi", "50"))
                .thenReturn(Flux.just("第一段\n第二段\r\n第三段"));

        List<String> lines = chatService.streamReactive(req).collectList().block();

        assertTrue(lines.contains("event: token\ndata: 第一段\\n第二段\\r\\n第三段"),
                "换行应转义为 \\n/\\r 字面量并保持在同一条 token 块内, 实际输出: " + lines);
        // 不允许出现任何裸内容行（不带 data:/event: 前缀的非空行）
        assertTrue(lines.stream().noneMatch(l -> !l.startsWith("data:") && !l.startsWith("event:")),
                "流中不得出现脱前缀的物理断行, 实际输出: " + lines);
        // 写回记忆仍为 user+assistant 两条（转义只发生在传输层，不污染存储原文）
        verify(sessionService, times(2)).saveContext(eq("50"), any());
    }
}