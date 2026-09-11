package com.dark.javaHarness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.domain.LlmCallLog;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.service.impl.LlmCallRecorder;
import com.dark.javaHarness.tool.ToolAssignments;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Flux;

/**
 * 路径 A 执行脚手架行为基线（GeneralAssistantAgent）：
 * 锁定执行层与观测层的可观察语义，作为「执行收敛到 AgentChatCaller」重构的等价性裁判。
 *
 * <p>刻意只断言信道无关的语义（内容/顺序/记录成败与归因/估算口径），不断言
 * call/stream 信道选择与 stream 标志——重构后 execute 统一走流式信道（AgentChatCaller
 * 既有约定：流式是唯一可中止通道），该差异是有意的、已记录的行为变化。
 *
 * <p>覆盖点：阻塞 execute 成败记录、响应式流式的逐 token 透传/空帧跳过/真实 usage
 * 末帧/错误记录/取消记录、阻塞 executeStream 的回调与记录。
 */
@ExtendWith(MockitoExtension.class)
class GeneralAssistantAgentScaffoldTest {

    /** 文本 token → ChatResponse 流（生产流式链走 chatResponse 通道以捕获 streamUsage 末帧） */
    private static Flux<ChatResponse> fluxOf(String... tokens) {
        return Flux.fromArray(List.of(tokens).stream()
                .map(t -> new ChatResponse(List.of(new Generation(new AssistantMessage(t)))))
                .toArray(ChatResponse[]::new));
    }

    /** 文本 token + 末帧 usage 空帧（streamOptions.include_usage 末帧形态，contentOf=null） */
    private static Flux<ChatResponse> fluxWithUsage(int prompt, int completion, int total, String... tokens) {
        List<ChatResponse> frames = new ArrayList<>();
        for (String t : tokens) {
            frames.add(new ChatResponse(List.of(new Generation(new AssistantMessage(t)))));
        }
        frames.add(new ChatResponse(List.of(), ChatResponseMetadata.builder()
                .usage(new DefaultUsage(prompt, completion, total)).build()));
        return Flux.fromIterable(frames);
    }

    @Mock
    private ChatClientRegistry clientRegistry;
    @Mock
    private SessionService memoryStore;
    @Mock
    private AgentService agentService;
    @Mock
    private ToolAssignments toolAssignments;
    @Mock
    private LlmCallRecorder recorder;
    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.CallResponseSpec callSpec;
    @Mock
    private ChatClient.StreamResponseSpec streamSpec;

    private GeneralAssistantAgent agent;

    @BeforeEach
    void setUp() {
        agent = new GeneralAssistantAgent("general", clientRegistry, memoryStore, agentService,
                toolAssignments, recorder);
        when(agentService.getAgentConfig("general"))
                .thenReturn(Optional.of(new AgentConfig(1L, "m1", "系统提示词", null)));
        lenient().when(toolAssignments.forAgent(any())).thenReturn(ToolAssignments.ToolSet.EMPTY);
        lenient().when(memoryStore.get(anyString())).thenReturn(List.of());
        when(clientRegistry.get(1L)).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        // 请求链公共段：返回值全部丢弃（build 只用 spec 变量），仅 prompt/system/user/advisors 需回链
        lenient().when(requestSpec.system(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.user(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.advisors(any(Advisor.class))).thenReturn(requestSpec);
        // 两条信道都预置（重构前后 execute 分别走 call/stream），各测试只触发其一
        lenient().when(requestSpec.call()).thenReturn(callSpec);
        lenient().when(requestSpec.stream()).thenReturn(streamSpec);
    }

    private static ChatResponse textResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** 阻塞 execute：返回完整回复 + 落一条成功记录（归因 sessionId/agent/model） */
    @Test
    void execute_success_returnsReplyAndRecordsOk() {
        when(streamSpec.chatResponse()).thenReturn(Flux.just(textResponse("完整回答")));

        String reply = agent.execute(new Goal("g1", "问题", "s1"));

        assertEquals("完整回答", reply);
        LlmCallLog row = capturedSingle();
        assertEquals("s1", row.sessionId());
        assertEquals("general", row.agentName());
        assertEquals("m1", row.model());
        assertTrue(row.ok());
        assertNull(row.errorMsg());
    }

    /** 阻塞 execute：模型报错 → 重抛 + 落一条失败记录（错误描述经原因链展开） */
    @Test
    void execute_failure_recordsErrorAndRethrows() {
        when(streamSpec.chatResponse()).thenReturn(Flux.error(new RuntimeException("boom-401无效")));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> agent.execute(new Goal("g2", "问题", "s2")));
        assertTrue(ex.getMessage().contains("boom-401无效"));

        LlmCallLog row = capturedSingle();
        assertEquals("s2", row.sessionId());
        assertFalse(row.ok());
        assertTrue(row.errorMsg() != null && row.errorMsg().contains("boom-401无效"));
    }

    /** 响应式流式：逐 token 原样透传；无 usage 回包时按输出估算且置 estimated */
    @Test
    void executeStreamReactive_tokensProgressively_estimatedRecord() {
        when(streamSpec.chatResponse()).thenReturn(fluxOf("你", "好"));

        List<String> out = agent.executeStreamReactive(new Goal("g3", "问题", "s3"))
                .collectList()
                .block();

        assertEquals(List.of("你", "好"), out);
        LlmCallLog row = capturedSingle();
        assertTrue(row.ok());
        assertTrue(row.stream());
        assertNull(row.promptTokens());
        assertTrue(row.tokensEstimated());
        assertTrue(row.completionTokens() != null && row.completionTokens() > 0);
        assertEquals(row.completionTokens(), row.totalTokens());
    }

    /** 响应式流式：streamUsage 末帧空帧被跳过（内容不变）且记录真实 usage（不置估算） */
    @Test
    void executeStreamReactive_usageFinalFrame_realUsageRecorded() {
        when(streamSpec.chatResponse()).thenReturn(fluxWithUsage(21, 21, 42, "你好"));

        List<String> out = agent.executeStreamReactive(new Goal("g4", "问题", "s4"))
                .collectList()
                .block();

        assertEquals(List.of("你好"), out);
        LlmCallLog row = capturedSingle();
        assertTrue(row.ok());
        assertFalse(row.tokensEstimated());
        assertEquals(21, row.promptTokens());
        assertEquals(21, row.completionTokens());
        assertEquals(42, row.totalTokens());
    }

    /** 响应式流式：流中错误 → 异常上抛 + 落失败记录（真实原因，非泛化文案） */
    @Test
    void executeStreamReactive_error_recordsErrorAndPropagates() {
        when(streamSpec.chatResponse())
                .thenReturn(Flux.concat(fluxOf("你"), Flux.error(new RuntimeException("供应商 5xx 炸了"))));

        assertThrows(RuntimeException.class,
                () -> agent.executeStreamReactive(new Goal("g5", "问题", "s5")).collectList().block());

        LlmCallLog row = capturedSingle();
        assertFalse(row.ok());
        assertTrue(row.stream());
        assertTrue(row.errorMsg() != null && row.errorMsg().contains("供应商 5xx 炸了"), "row=" + row);
    }

    /** 响应式流式：订阅取消 → 落取消记录（可执行原因），不按成功计 */
    @Test
    void executeStreamReactive_cancel_recordsCancelError() throws Exception {
        when(streamSpec.chatResponse()).thenReturn(Flux.concat(fluxOf("首token"), Flux.never()));
        CountDownLatch firstToken = new CountDownLatch(1);
        CountDownLatch recorded = new CountDownLatch(1);
        AtomicReference<Subscription> subRef = new AtomicReference<>();
        doAnswer(inv -> {
            recorded.countDown();
            return null;
        }).when(recorder).record(any());

        agent.executeStreamReactive(new Goal("g6", "问题", "s6"))
                .subscribe(t -> firstToken.countDown(), e -> { }, () -> { },
                        s -> { subRef.set(s); s.request(Long.MAX_VALUE); });
        assertTrue(firstToken.await(2, TimeUnit.SECONDS), "首个 token 应到达");
        subRef.get().cancel();
        assertTrue(recorded.await(2, TimeUnit.SECONDS), "取消后应落观测记录");

        LlmCallLog row = capturedSingle();
        assertFalse(row.ok());
        assertTrue(row.stream());
        assertTrue(row.errorMsg() != null && row.errorMsg().contains("客户端断开"));
    }

    /** 阻塞 executeStream：onToken 逐 token 回调 + 落成功记录 */
    @Test
    void executeStream_onTokenReceivesTokensAndRecordsOk() {
        when(streamSpec.chatResponse()).thenReturn(fluxOf("你", "好"));
        List<String> got = new ArrayList<>();

        agent.executeStream(new Goal("g7", "问题", "s7"), got::add);

        assertEquals(List.of("你", "好"), got);
        LlmCallLog row = capturedSingle();
        assertTrue(row.ok());
        assertEquals("s7", row.sessionId());
    }

    private LlmCallLog capturedSingle() {
        ArgumentCaptor<LlmCallLog> captor = ArgumentCaptor.forClass(LlmCallLog.class);
        verify(recorder).record(captor.capture());
        return captor.getValue();
    }
}
