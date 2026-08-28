package com.dark.javaHarness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.tool.ToolAssignments;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.client.ChatClient.StreamResponseSpec;
import reactor.core.publisher.Flux;

/**
 * MultiAgentGraphAgent 单测：
 * - 能构建 StateGraph 并执行（lead 拆解 → 并行子任务 → 聚合）
 * - lead 返回合法 JSON 时，execute() 返回最终回答（非空）
 * - 流式执行同时发射进度行与内容行，且阶段次序符合「编排→拆解→子任务→聚合→内容」
 * - 专家派遣：子任务按专家名查配置取对应客户端；白名单外回退默认
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
    private StreamResponseSpec streamSpec;
    @Mock
    private AgentService agentService;
    @Mock
    private ToolAssignments toolAssignments;

    private MultiAgentGraphAgent agent;

    /** lead 返回两条子任务（旧纯字符串格式），子任务/聚合返回固定文本 */
    private String fixedContent() {
        return "{\"subtasks\":[\"子任务A\",\"子任务B\"]}";
    }

    private void stubChat(String content) {
        when(clientRegistry.get(any())).thenReturn(chatClient);
        when(agentService.getAgentConfig(anyString())).thenReturn(java.util.Optional.empty());
        lenient().when(toolAssignments.forAgent(any())).thenReturn(ToolAssignments.ToolSet.EMPTY);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(content);
        // 流式执行时聚合节点走 stream 通道（lead/子任务仍走 call）
        lenient().when(requestSpec.stream()).thenReturn(streamSpec);
        lenient().when(streamSpec.content()).thenReturn(Flux.just(content));
    }

    /** 独立 stub 的客户端：专家子任务使用，内容固定（避免与共享 mock 的 stub 冲突） */
    private ChatClient newStubbedClient(String content) {
        ChatClient c = mock(ChatClient.class);
        ChatClientRequestSpec rs = mock(ChatClientRequestSpec.class);
        CallResponseSpec cs = mock(CallResponseSpec.class);
        StreamResponseSpec ss = mock(StreamResponseSpec.class);
        when(c.prompt()).thenReturn(rs);
        when(rs.system(anyString())).thenReturn(rs);
        when(rs.user(anyString())).thenReturn(rs);
        when(rs.call()).thenReturn(cs);
        when(cs.content()).thenReturn(content);
        lenient().when(rs.stream()).thenReturn(ss);
        lenient().when(ss.content()).thenReturn(Flux.just(content));
        return c;
    }

    @Test
    void execute_shouldBuildGraphAndReturnFinalAnswer() {
        stubChat(fixedContent());
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService, toolAssignments);

        Goal goal = new Goal("g1", "调研竞品并输出报告");
        String reply = agent.execute(goal);

        assertNotNull(reply);
        assertFalse(reply.isBlank(), "execute 应产出最终回答");
        // lead 拆解与聚合按独立角色行查配置（与编排器 multi-agent 解耦）
        verify(agentService).getAgentConfig("lead");
        verify(agentService).getAgentConfig("aggregator");
        verify(agentService, never()).getAgentConfig("multi-agent");
    }

    @Test
    void execute_returnsNonEmptyForAggregate() {
        // 所有节点统一返回固定 content；lead 解析出 subtasks，聚合把该 content 作为最终回答
        stubChat(fixedContent());
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService, toolAssignments);

        String reply = agent.execute(new Goal("g2", "hello"));

        assertNotNull(reply);
        assertEquals(fixedContent(), reply, "聚合节点把读取到的结果作为最终回答返回");
    }

    /** executeStreamReactive 应同时发射：进度行（ProgressLine.MARK 前缀）+ 内容行（最终回答） */
    @Test
    void executeStreamReactive_emitsProgressRowsAndContent() {
        stubChat(fixedContent());
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService, toolAssignments);

        Goal goal = new Goal("g3", "调研竞品并输出报告");
        java.util.List<String> rows = agent.executeStreamReactive(goal).collectList().block();

        assertNotNull(rows);
        assertFalse(rows.isEmpty());
        long progressCount = rows.stream()
                .filter(r -> r.length() > 0 && r.charAt(0) == ProgressLine.MARK)
                .count();
        long contentCount = rows.stream()
                .filter(r -> r.length() > 0 && r.charAt(0) != ProgressLine.MARK)
                .count();
        assertTrue(progressCount > 0, "应发射进度行, 实际帧序列: " + rows);
        assertTrue(contentCount > 0, "应发射内容行(最终回答), 实际帧序列: " + rows);
        assertEquals(fixedContent(), rows.get(rows.size() - 1), "内容行应为聚合产出的最终回答");
    }

    @Test
    void executeStreamReactive_streamsStageEventsInOrder() {
        stubChat(fixedContent());
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService, toolAssignments);

        java.util.List<String> rows = agent
                .executeStreamReactive(new Goal("g4", "调研竞品并输出报告"))
                .collectList()
                .block();

        assertNotNull(rows);
        assertFalse(rows.isEmpty());

        // 解析出各行的 (stage, isContent)
        java.util.List<ParsedRow> parsed = new java.util.ArrayList<>();
        for (String r : rows) {
            if (!r.isEmpty() && r.charAt(0) == ProgressLine.MARK) {
                ProgressLine.StageRow p = ProgressLine.decode(r);
                parsed.add(new ParsedRow(p.stage(), false));
            } else {
                parsed.add(new ParsedRow(null, true));
            }
        }

        // 1. 首帧为「编排」进度行
        assertEquals("编排", parsed.get(0).stage(), "首行应为编排开始的进度行");
        assertFalse(parsed.get(0).content());

        // 2. 存在「拆解」，且位于首帧之后、内容行之前
        int leadIdx = indexOfStage(parsed, "拆解");
        assertTrue(leadIdx > 0, "应存在 lead 拆解的进度行");

        // 3. 至少 2 条「子任务」完成事件（生命周期钩子 after(subtask-N) 补齐并行分支），
        //    且都位于最终内容行之前
        long subtaskCount = parsed.stream().filter(r -> "子任务".equals(r.stage())).count();
        assertTrue(subtaskCount >= 2, "应有两条并行子任务完成事件, 实际帧序列: " + rows);
        int lastIdx = rows.size() - 1;
        for (int i = 0; i < parsed.size(); i++) {
            if ("子任务".equals(parsed.get(i).stage())) {
                assertTrue(i < lastIdx, "子任务完成事件应在最终回答之前");
                assertFalse(parsed.get(i).content());
            }
        }

        // 4. 最后一个有效帧：内容行 = 最终回答，且前面紧邻「聚合」进度
        ParsedRow last = parsed.get(parsed.size() - 1);
        assertTrue(last.content(), "流应以最终回答内容行收尾");
        assertEquals(fixedContent(), rows.get(rows.size() - 1), "内容行应为聚合产出的最终回答");
        assertEquals("聚合", parsed.get(parsed.size() - 2).stage(), "内容行前应紧邻聚合进度行");
    }

    /** 聚合节点逐 token：多段 token 按序发射、拼接=完整内容，「聚合」进度在首 token 之前，主干不重复发射 */
    @Test
    void executeStreamReactive_streamsFinalAnswerTokenByToken() {
        stubChat(fixedContent());
        // 覆盖聚合节点的 stream 内容：三段 token（含一个空片段，模拟真实流的空 token）
        when(streamSpec.content())
                .thenReturn(Flux.just("最终回答", "", "第二段", "。"));
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService, toolAssignments);

        java.util.List<String> rows = agent
                .executeStreamReactive(new Goal("g8", "调研竞品并输出报告"))
                .collectList()
                .block();

        assertNotNull(rows);
        // 尾部应为：... 聚合进度, token1, token2, token3（空 token 不发射）
        int n = rows.size();
        assertEquals("最终回答", rows.get(n - 3), "token1 应倒数第三");
        assertEquals("第二段", rows.get(n - 2), "token2 应倒数第二（空 token 被跳过）");
        assertEquals("。", rows.get(n - 1), "token3 应收尾");
        ProgressLine.StageRow agg = ProgressLine.decode(rows.get(n - 4));
        assertNotNull(agg, "首 token 前应有聚合进度行");
        assertEquals("聚合", agg.stage(), "聚合进度应紧邻首 token 之前");

        // 全流拼接的内容 = token 序列拼接（主干不重复发完整内容）
        StringBuilder content = new StringBuilder();
        for (String r : rows) {
            if (r.isEmpty() || r.charAt(0) != ProgressLine.MARK) {
                content.append(r);
            }
        }
        assertEquals("最终回答第二段。", content.toString(), "所有内容行拼接应为完整最终回答");
    }

    /** 解析行容器：stage（进度行非空，内容行为 null）+ 是否内容行 */
    private record ParsedRow(String stage, boolean content) {
    }

    /** 找指定阶段的进度行索引，无则 -1 */
    private static int indexOfStage(java.util.List<ParsedRow> parsed, String stage) {
        for (int i = 0; i < parsed.size(); i++) {
            if (stage.equals(parsed.get(i).stage())) {
                return i;
            }
        }
        return -1;
    }

    /** lead 返回新格式（对象数组带专家指派），专家行有配置 → 子任务按专家 model 取对应客户端 */
    @Test
    void execute_dispatchesSubtasksToExpertClients() {
        String leadJson = "{\"subtasks\":[{\"desc\":\"调研竞品\",\"agent\":\"researcher\"},"
                + "{\"desc\":\"统计销量\",\"agent\":\"analyst\"}]}";
        stubChat(leadJson); // 默认客户端：lead + 聚合（get(any()) 兜底）
        // 专家行有配置 → 子任务按专家 model 取对应客户端
        when(agentService.getAgentConfig(eq("researcher")))
                .thenReturn(java.util.Optional.of(new com.dark.javaHarness.domain.AgentConfig("qwen-plus", "调研提示词")));
        when(agentService.getAgentConfig(eq("analyst")))
                .thenReturn(java.util.Optional.of(new com.dark.javaHarness.domain.AgentConfig("deepseek-chat", "分析提示词")));
        // 先建好独立 stub 的专家客户端，再注册（避免在 when() 求值内嵌套 stubbing）
        ChatClient researcherClient = newStubbedClient("调研结果");
        ChatClient analystClient = newStubbedClient("分析结果");
        when(clientRegistry.get(eq("qwen-plus"))).thenReturn(researcherClient);
        when(clientRegistry.get(eq("deepseek-chat"))).thenReturn(analystClient);
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService, toolAssignments);

        String reply = agent.execute(new Goal("g5", "调研竞品并统计销量"));

        assertNotNull(reply);
        assertFalse(reply.isBlank(), "专家派遣后仍应产出聚合最终回答");
        // 子任务按指派查询专家配置并取对应模型客户端
        verify(agentService).getAgentConfig("researcher");
        verify(agentService).getAgentConfig("analyst");
        verify(clientRegistry).get("qwen-plus");
        verify(clientRegistry).get("deepseek-chat");
    }

    /** lead 指派了白名单外的专家名 → 拒绝按非法名查配置/取客户端，回退默认执行 */
    @Test
    void execute_rejectsUnknownAgentAndFallsBackToDefault() {
        String leadJson = "{\"subtasks\":[{\"desc\":\"任务X\",\"agent\":\"hacker\"}]}";
        stubChat(leadJson);
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService, toolAssignments);

        String reply = agent.execute(new Goal("g6", "任务X"));

        assertNotNull(reply);
        // 白名单拒绝：绝不以非法名查配置、取客户端
        verify(agentService, never()).getAgentConfig("hacker");
        verify(clientRegistry, never()).get("hacker");
    }

    /** agent 表无任何配置（含 null model）→ 走默认客户端，model 参数不应被设置 */
    @Test
    void execute_withoutConfig_usesDefaultClientWithoutModelOption() {
        stubChat(fixedContent());
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService, toolAssignments);

        String reply = agent.execute(new Goal("g7", "hello"));

        assertEquals(fixedContent(), reply);
        // lead + 2 个并行子任务 + 聚合共 4 次调用，全部走默认客户端（model=null）
        verify(clientRegistry, org.mockito.Mockito.times(4)).get(isNull());
        verify(requestSpec, never()).options(any());
    }

    /**
     * 客户端断连（cancel）→ doFinally 置位短路标志 → 后续节点不再发起新的 LLM 调用：
     * lead 调用阻塞期间 dispose，释放后除 lead 外不应有任何新调用（子任务/聚合全部短路）。
     * subscribeOn 模拟 AgentServiceImpl 的真实订阅方式（图执行与 cancel 分属不同线程）。
     */
    @Test
    void executeStreamReactive_cancelDuringLead_skipsRemainingLlmCalls() throws Exception {
        // 独立 stub：lead 的 call() 阻塞在闩锁上，模拟「调用进行中客户端断开」
        java.util.concurrent.CountDownLatch releaseLead = new java.util.concurrent.CountDownLatch(1);
        when(clientRegistry.get(any())).thenReturn(chatClient);
        when(agentService.getAgentConfig(anyString())).thenReturn(java.util.Optional.empty());
        lenient().when(toolAssignments.forAgent(any())).thenReturn(ToolAssignments.ToolSet.EMPTY);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        // lenient：短路生效时 lead 可能一次都不调用（UnnecessaryStubbing 免检）
        lenient().when(requestSpec.call()).thenAnswer(inv -> {
            releaseLead.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return responseSpec;
        });
        lenient().when(responseSpec.content()).thenReturn(fixedContent());
        lenient().when(requestSpec.stream()).thenReturn(streamSpec);
        lenient().when(streamSpec.content()).thenReturn(Flux.just(fixedContent()));
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService, toolAssignments);

        // subscribeOn 让图执行离开测试线程（否则同步图驱动会把 subscribe() 卡在 lead 阻塞上）
        reactor.core.Disposable disposable = agent
                .executeStreamReactive(new Goal("g9", "调研竞品并输出报告"))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .subscribe();
        Thread.sleep(300);                    // 等图启动、lead 进入 call() 阻塞

        disposable.dispose();                 // 模拟客户端断开 → cancel → 置位短路标志
        releaseLead.countDown();              // 进行中的 lead 调用自然结束
        Thread.sleep(500);                    // 给「短路失效则子任务会发起新调用」留暴露窗口

        // 时序兼容：lead 或在置位前已发起调用（1 次）、或被更早短路（0 次），但绝不能再多
        verify(requestSpec, org.mockito.Mockito.atMost(1)).call();
        verify(requestSpec, never()).stream(); // 聚合流式调用也不应发起
    }
}
