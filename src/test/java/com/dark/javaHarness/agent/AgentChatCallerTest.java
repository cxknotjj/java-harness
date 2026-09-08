package com.dark.javaHarness.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.config.ContextBudgetProperties;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.service.AgentService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

    /**
     * 文本 token + 末帧 usage（total 为该轮完整 prompt+completion 的累计口径）→ ChatResponse 流。
     * 末帧只含 usage 无 generations（contentOf=null，与生产 streamUsage 空帧同形态）。
     */
    private static Flux<ChatResponse> fluxWithUsage(int total, String... tokens) {
        List<ChatResponse> frames = new ArrayList<>();
        for (String t : tokens) {
            frames.add(new ChatResponse(List.of(new Generation(new AssistantMessage(t)))));
        }
        frames.add(new ChatResponse(List.of(), org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                .usage(new org.springframework.ai.chat.metadata.DefaultUsage(total / 2, total - total / 2, total))
                .build()));
        return Flux.fromIterable(frames);
    }

    /**
     * 预算账本桩：记录每次 recordUsage（[totalTokens, estimated]），overBudget 按
     * 「已入账累计 ≥ limit」判定（limit=0 恒不熔断）；preSpent 模拟前序调用已消耗。
     */
    private static final class LedgerStub implements AgentChatCaller.BudgetLedger {
        final long limit;
        final java.util.concurrent.atomic.AtomicLong spent = new java.util.concurrent.atomic.AtomicLong();
        final List<long[]> records = new ArrayList<>();

        LedgerStub(long limit) {
            this(limit, 0);
        }

        LedgerStub(long limit, long preSpent) {
            this.limit = limit;
            this.spent.set(preSpent);
        }

        @Override
        public boolean overBudget() {
            return limit > 0 && spent.get() >= limit;
        }

        @Override
        public void recordUsage(int totalTokens, boolean estimated) {
            records.add(new long[]{totalTokens, estimated ? 1 : 0});
            spent.addAndGet(totalTokens);
        }
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

    /* ---------------- 输出封顶（maxTokens 角色档位） ---------------- */

    /**
     * 档位映射：lead → lead 档；aggregator → final 档（与路径 A 直出同档）；
     * 子任务专家（researcher）→ expert 档。捕获请求级 ChatOptions.maxTokens 断言。
     * 配置类零默认（数值唯一来源是 yaml），此处显式设值验证映射机制本身。
     */
    @Test
    @org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
    void call_maxTokensTierMapping_byRole() {
        ContextBudgetProperties budgets = new ContextBudgetProperties();
        budgets.setMaxTokensLead(1000);
        budgets.setMaxTokensFinal(3000);
        budgets.setMaxTokensExpert(2000);
        AgentChatCaller tiered = new AgentChatCaller(clientRegistry, agentService, null, null,
                new LlmRetry(), budgets);
        when(agentService.getAgentConfig("lead"))
                .thenReturn(Optional.of(new AgentConfig(1L, "m1", "系统提示词")));
        when(agentService.getAgentConfig("aggregator"))
                .thenReturn(Optional.of(new AgentConfig(1L, "m1", "系统提示词")));
        when(streamSpec.chatResponse()).thenReturn(fluxOf("ok"));

        tiered.call("s1", "lead", "兜底", "任务", null);
        tiered.call("s1", "aggregator", "兜底", "任务", null);
        tiered.call("s1", "researcher", "兜底", "任务", null);

        ArgumentCaptor<OpenAiChatOptions> captor = ArgumentCaptor.forClass(OpenAiChatOptions.class);
        verify(spec, times(3)).options(captor.capture());
        assertEquals(List.of(1000, 3000, 2000),
                captor.getAllValues().stream().map(OpenAiChatOptions::getMaxTokens).toList(),
                "lead/aggregator/专家 应分别落 lead/final/expert 三档");
    }

    /** 0 = 不限制（统一口径，也是配置类缺省值）：档位 0 时不写入 maxTokens，保持模型默认 */
    @Test
    void call_maxTokensZero_notSet() {
        when(streamSpec.chatResponse()).thenReturn(fluxOf("ok"));

        caller.call("s1", "researcher", "兜底", "任务", null);

        ArgumentCaptor<OpenAiChatOptions> captor = ArgumentCaptor.forClass(OpenAiChatOptions.class);
        verify(spec, times(1)).options(captor.capture());
        assertNull(captor.getValue().getMaxTokens(), "档位 0 时不得设置 maxTokens");
    }

    /* ---------------- 预算账本（BudgetLedger 门控 + 按 roundtrip 增量记账） ---------------- */

    /** 无真实 usage 回包：按输出文本估算入账且 estimated=true（口径与 tokens_estimated 一致） */
    @Test
    void call_reportsEstimatedUsageToListener() {
        when(streamSpec.chatResponse()).thenReturn(fluxOf("你好", "世界"));
        LedgerStub ledger = new LedgerStub(0);

        caller.call("s1", "researcher", "兜底", "任务", null, new Advisor[0], null, ledger);

        assertEquals(1, ledger.records.size(), "成功调用结束应入账一次");
        assertTrue(ledger.records.get(0)[0] > 0, "估算 token 应大于 0");
        assertEquals(1, ledger.records.get(0)[1], "无 usage 回包应标记估算口径");
    }

    /** 真实 usage 帧：按该轮 total 全额入账（estimated=false），不触发估算兜底 */
    @Test
    void call_realUsageFrame_recordsActualTokens() {
        when(streamSpec.chatResponse()).thenReturn(fluxWithUsage(150, "回答"));
        LedgerStub ledger = new LedgerStub(0);

        String out = caller.call("s1", "researcher", "兜底", "任务", null, new Advisor[0], null, ledger);

        assertEquals("回答", out);
        assertEquals(1, ledger.records.size());
        assertArrayEquals(new long[]{150, 0}, ledger.records.get(0), "usage 帧按该轮 total 真实入账");
    }

    /** 发起前已超限：零 HTTP 短路（拒绝发起新调用），不产生任何入账 */
    @Test
    @org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
    void call_ledgerPreCheckOverBudget_zeroHttp() {
        LedgerStub ledger = new LedgerStub(100, 100); // 前序调用已耗尽预算

        assertThrows(AgentChatCaller.BudgetExceededException.class,
                () -> caller.call("s1", "researcher", "兜底", "任务", null, new Advisor[0], null, ledger),
                "超限后调用应被熔断拒绝");

        verify(client, never()).prompt();
        assertTrue(ledger.records.isEmpty(), "拒绝发起不产生入账");
    }

    /**
     * roundtrip 中的 usage 帧触发熔断：累计入账达到上限即断流抛异常（即使本轮已消耗——
     * 并行扇出/工具循环下「烧穿剩余预算」由此封顶），熔断前的增量入账保留（账本可读）。
     */
    @Test
    void call_usageFrameTripsBudget_midStreamAbort() {
        // 累计口径 usage 帧：total 60 → delta 60（账本 60 < 100 放行）；total 120 → delta 60
        // （账本 120 ≥ 100 熔断断流）
        when(streamSpec.chatResponse()).thenReturn(Flux.concat(
                fluxWithUsage(60, "部分"),
                fluxWithUsage(120, "续")));
        LedgerStub ledger = new LedgerStub(100);

        assertThrows(AgentChatCaller.BudgetExceededException.class,
                () -> caller.call("s1", "researcher", "兜底", "任务", null, new Advisor[0], null, ledger),
                "usage 帧累计入账达上限应断流");

        assertEquals(2, ledger.records.size(), "熔断前每轮增量应已入账");
        assertEquals(120L, ledger.spent.get(), "账本累计应为已入账增量之和");
    }

    /** 账本为 null（无账本场景）：usage 帧捕获照旧（llm_call_log 口径不变），不熔断不记账 */
    @Test
    void call_nullLedger_noOp() {
        when(streamSpec.chatResponse()).thenReturn(fluxWithUsage(150, "ok"));

        assertEquals("ok", caller.call("s1", "researcher", "兜底", "任务",
                null, new Advisor[0], null, null));
    }

    /* ---------------- 流式路径账本上报（I1：stream 与 call 同口径） ---------------- */

    /** stream + 真实 usage 帧：增量入账（estimated=false）——同步/流式口径一致 */
    @Test
    void stream_reportsRealUsageToLedger() {
        when(streamSpec.chatResponse()).thenReturn(fluxWithUsage(150, "聚合", "结果"));
        LedgerStub ledger = new LedgerStub(0);
        StringBuilder collected = new StringBuilder();

        caller.stream("s1", "researcher", "兜底", "任务",
                collected::append, null, new Advisor[0], null, ledger);

        assertEquals("聚合结果", collected.toString());
        assertEquals(1, ledger.records.size());
        assertArrayEquals(new long[]{150, 0}, ledger.records.get(0), "流式路径应与 call 同口径入账真实 usage");
    }

    /** stream 无 usage 回包：按输出估算兜底入账（estimated=true） */
    @Test
    void stream_noUsage_estimatedFallback() {
        when(streamSpec.chatResponse()).thenReturn(fluxOf("a", "b"));
        LedgerStub ledger = new LedgerStub(0);

        caller.stream("s1", "researcher", "兜底", "任务",
                token -> { }, null, new Advisor[0], null, ledger);

        assertEquals(1, ledger.records.size());
        assertTrue(ledger.records.get(0)[0] > 0, "估算 token 应大于 0");
        assertEquals(1, ledger.records.get(0)[1], "无 usage 回包应标记估算口径");
    }

    /** stream 发起前已超限：零 HTTP 短路 */
    @Test
    @org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
    void stream_ledgerPreCheckOverBudget_zeroHttp() {
        LedgerStub ledger = new LedgerStub(100, 100);

        assertThrows(AgentChatCaller.BudgetExceededException.class,
                () -> caller.stream("s1", "researcher", "兜底", "任务",
                        token -> { }, null, new Advisor[0], null, ledger),
                "超限后流式调用应被熔断拒绝");

        verify(client, never()).prompt();
    }
}
