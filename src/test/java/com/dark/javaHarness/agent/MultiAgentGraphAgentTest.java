package com.dark.javaHarness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.advisor.ContextAssemblingAdvisor;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.tool.ToolAssignments;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.client.ChatClient.StreamResponseSpec;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
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
    private StreamResponseSpec streamSpec;
    @Mock
    private AgentService agentService;
    @Mock
    private ToolAssignments toolAssignments;
    @Mock
    private SessionService memoryStore;

    private MultiAgentGraphAgent agent;

    /** lead 返回两条子任务（旧纯字符串格式），子任务/聚合返回固定文本 */
    private String fixedContent() {
        return "{\"subtasks\":[\"子任务A\",\"子任务B\"]}";
    }

    private void stubChat(String content) {
        when(clientRegistry.get(any())).thenReturn(chatClient);
        when(agentService.getAgentConfig(any())).thenReturn(java.util.Optional.empty());
        lenient().when(toolAssignments.forAgent(any())).thenReturn(ToolAssignments.ToolSet.EMPTY);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        // call() 已统一流式背书：lead/子任务/聚合全部走 stream().content() 通道
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just(content));
    }

    /** 独立 stub 的客户端：专家子任务使用，内容固定（避免与共享 mock 的 stub 冲突） */
    private ChatClient newStubbedClient(String content) {
        ChatClient c = mock(ChatClient.class);
        ChatClientRequestSpec rs = mock(ChatClientRequestSpec.class);
        StreamResponseSpec ss = mock(StreamResponseSpec.class);
        when(c.prompt()).thenReturn(rs);
        when(rs.system(anyString())).thenReturn(rs);
        when(rs.user(anyString())).thenReturn(rs);
        when(rs.stream()).thenReturn(ss);
        when(ss.content()).thenReturn(Flux.just(content));
        return c;
    }

    @Test
    void execute_shouldBuildGraphAndReturnFinalAnswer() {
        stubChat(fixedContent());
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService, toolAssignments, null);

        Goal goal = new Goal("g1", "调研竞品并输出报告");
        String reply = agent.execute(goal);

        assertNotNull(reply);
        assertFalse(reply.isBlank(), "execute 应产出最终回答");
        // lead 拆解与聚合按独立角色行查配置（与编排器 multi-agent 解耦）；
        // system 经 PromptAssembler 组装，角色段查表 + 调用器查表共至少两次
        verify(agentService, atLeastOnce()).getAgentConfig("lead");
        verify(agentService, atLeastOnce()).getAgentConfig("aggregator");
        verify(agentService, never()).getAgentConfig("multi-agent");
    }

    @Test
    void execute_returnsNonEmptyForAggregate() {
        // 所有节点统一返回固定 content；lead 解析出 subtasks，聚合把该 content 作为最终回答
        stubChat(fixedContent());
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService, toolAssignments, null);

        String reply = agent.execute(new Goal("g2", "hello"));

        assertNotNull(reply);
        assertEquals(fixedContent(), reply, "聚合节点把读取到的结果作为最终回答返回");
    }

    /** executeStreamReactive 应同时发射：进度行（ProgressLine.MARK 前缀）+ 内容行（最终回答） */
    @Test
    void executeStreamReactive_emitsProgressRowsAndContent() {
        stubChat(fixedContent());
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService, toolAssignments, null);

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
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService, toolAssignments, null);

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
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService, toolAssignments, null);

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
        // 专家行有配置 → 子任务按专家部署模型 id 取对应客户端
        when(agentService.getAgentConfig(eq("researcher")))
                .thenReturn(java.util.Optional.of(new com.dark.javaHarness.domain.AgentConfig(101L, "qwen-plus", "调研提示词")));
        when(agentService.getAgentConfig(eq("analyst")))
                .thenReturn(java.util.Optional.of(new com.dark.javaHarness.domain.AgentConfig(102L, "deepseek-chat", "分析提示词")));
        // 先建好独立 stub 的专家客户端，再注册（避免在 when() 求值内嵌套 stubbing）
        ChatClient researcherClient = newStubbedClient("调研结果");
        ChatClient analystClient = newStubbedClient("分析结果");
        when(clientRegistry.get(eq(101L))).thenReturn(researcherClient);
        when(clientRegistry.get(eq(102L))).thenReturn(analystClient);
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService, toolAssignments, null);

        String reply = agent.execute(new Goal("g5", "调研竞品并统计销量"));

        assertNotNull(reply);
        assertFalse(reply.isBlank(), "专家派遣后仍应产出聚合最终回答");
        // 子任务按指派查询专家配置并取对应部署模型的客户端（persona/请求组装各查一次）
        verify(agentService, atLeastOnce()).getAgentConfig("researcher");
        verify(agentService, atLeastOnce()).getAgentConfig("analyst");
        verify(clientRegistry).get(101L);
        verify(clientRegistry).get(102L);
    }

    /** lead 指派了白名单外的专家名 → 拒绝按非法名查配置/取客户端，回退默认执行 */
    @Test
    void execute_rejectsUnknownAgentAndFallsBackToDefault() {
        String leadJson = "{\"subtasks\":[{\"desc\":\"任务X\",\"agent\":\"hacker\"}]}";
        stubChat(leadJson);
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService, toolAssignments, null);

        String reply = agent.execute(new Goal("g6", "任务X"));

        assertNotNull(reply);
        // 白名单拒绝：绝不以非法名查配置；客户端只允许走默认兜底（get(null)），不得按 id 取
        verify(agentService, never()).getAgentConfig("hacker");
        verify(clientRegistry, never()).get(any(Long.class));
    }

    /** agent 表无任何配置（含 null model）→ 走默认客户端，model 参数不应被设置 */
    @Test
    void execute_withoutConfig_usesDefaultClientWithoutModelOption() {
        stubChat(fixedContent());
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService, toolAssignments, null);

        String reply = agent.execute(new Goal("g7", "hello"));

        assertEquals(fixedContent(), reply);
        // lead + 2 个并行子任务 + 聚合共 4 次调用，全部走默认客户端（model=null）
        verify(clientRegistry, org.mockito.Mockito.times(4)).get(isNull());
        verify(requestSpec, never()).options(any());
    }

    /* ---------------- 静态 prompt 预算（PromptBudgetAdvisor 挂载） ---------------- */

    /**
     * 编排链路预算防线：lead 与聚合调用请求级挂载 PromptBudgetAdvisor，
     * 子任务调用不挂（其动态工具结果由 ToolCallBudget 管）。
     */
    @Test
    void execute_leadAndAggregateCallsCarryPromptBudgetAdvisor() {
        stubChat(fixedContent());
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService, toolAssignments, null,
                null, new com.dark.javaHarness.config.ContextBudgetProperties());

        agent.execute(new Goal("gb1", "调研竞品并输出报告"));

        // lead 1 次 + 聚合 1 次挂载预算 advisor；子任务不挂
        verify(requestSpec, org.mockito.Mockito.times(2))
                .advisors(any(com.dark.javaHarness.advisor.PromptBudgetAdvisor.class));
    }

    /* ---------------- 记忆动态注入（MemoryPolicy） ---------------- */

    /**
     * 记忆动态注入：lead 拆解带会话记忆 advisor（与路径 A 同口径：MessageChatMemoryAdvisor +
     * CONVERSATION_ID 参数 + ContextAssemblingAdvisor 预算裁剪），子任务专家（含未指派回退的
     * general 兜底专家身份）与聚合节点不挂任何记忆 advisor。
     */
    @Test
    void execute_leadWithSession_carriesMemoryAdvisor_subtasksAndAggregateDoNot() {
        // 一条指派 researcher、一条未指派（执行时回退 general 兜底专家身份）：两路子任务都不得挂记忆
        String leadJson = "{\"subtasks\":[{\"desc\":\"调研竞品\",\"agent\":\"researcher\"},{\"desc\":\"撰写摘要\"}]}";
        stubChat(leadJson);
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService, toolAssignments, null,
                null, null, memoryStore);

        agent.execute(new Goal("gm1", "调研竞品并撰写摘要", "sess-1"));

        // 仅 lead 1 次调用挂载：记忆 advisor + 会话 ID 参数 + 上下文裁剪
        verify(requestSpec, org.mockito.Mockito.times(1)).advisors(any(MessageChatMemoryAdvisor.class));
        verify(requestSpec, org.mockito.Mockito.times(1)).advisors(anyConsumer());
        verify(requestSpec, org.mockito.Mockito.times(1)).advisors(any(ContextAssemblingAdvisor.class));
    }

    /** 无会话 ID（Goal 未关联会话）→ lead 也不挂记忆 advisor（策略：无会话 ID 场景一律不注入） */
    @Test
    void execute_withoutSessionId_skipsMemoryAdvisor() {
        stubChat(fixedContent());
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService, toolAssignments, null,
                null, null, memoryStore);

        agent.execute(new Goal("gm2", "调研竞品并输出报告"));

        verify(requestSpec, never()).advisors(any(MessageChatMemoryAdvisor.class));
        verify(requestSpec, never()).advisors(anyConsumer());
        verify(requestSpec, never()).advisors(any(ContextAssemblingAdvisor.class));
    }

    /** 泛型辅助：匹配 Consumer 重载的 advisors(...)（记忆 advisor 的 CONVERSATION_ID 参数挂载），避免依赖具体嵌套类型名 */
    private static <T> Consumer<T> anyConsumer() {
        return any();
    }

    /* ---------------- 断点续跑（Checkpointer） ---------------- */

    /** 未启用检查点存储（构造传 null）→ resume 快速失败，抛出明确异常（无需 LLM 桩） */
    @Test
    void resume_withoutCheckpointer_throwsFast() {
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService, toolAssignments, null);

        assertThrows(IllegalStateException.class,
                () -> agent.resumeStreamReactive(new Goal("gr1", "调研竞品")),
                "未启用 checkpointer 应拒绝续跑");
    }

    /** 启用了检查点但该 goal 从未跑过编排（无检查点记录）→ resume 快速失败（无需 LLM 桩） */
    @Test
    void resume_withoutCheckpoint_throwsFast() {
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService, toolAssignments, null,
                new com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver());

        assertThrows(IllegalStateException.class,
                () -> agent.resumeStreamReactive(new Goal("gr2", "调研竞品")),
                "无检查点记录应拒绝续跑");
    }

    /**
     * 完整跑完一次编排后 resume：检查点已记录最终状态（nextNodeId=END），
     * 续跑不应再发起任何 LLM 调用，且应从检查点状态取回最终回答（END 帧兜底读 state.final）。
     */
    @Test
    void resume_afterCompletedRun_skipsAllLlmCalls() {
        stubChat(fixedContent());
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService, toolAssignments, null,
                new com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver());

        // 首跑：lead + 2 子任务 + 聚合 = 4 次流式调用（call 已统一流式背书），检查点逐 superstep 落库
        java.util.List<String> first = agent
                .executeStreamReactive(new Goal("gr3", "调研竞品并输出报告"))
                .collectList()
                .block();
        assertNotNull(first);
        assertEquals(fixedContent(), first.get(first.size() - 1), "首跑应以最终回答收尾");

        // 续跑：所有节点已完成，零 LLM 调用，从检查点状态直接取回最终回答
        java.util.List<String> resumed = agent
                .resumeStreamReactive(new Goal("gr3", "调研竞品并输出报告"))
                .collectList()
                .block();
        assertNotNull(resumed);
        assertEquals(fixedContent(), resumed.get(resumed.size() - 1),
                "续跑应从检查点状态取回最终回答, 实际帧序列: " + resumed);

        // stream 共 4 次（lead + 2 子任务 + 聚合），resume 零新增
        verify(requestSpec, org.mockito.Mockito.times(4)).stream();
    }

    /**
     * 中途断开后 resume 补执行缺口：
     * 时序：lead 正常完成落检查点 → 子任务批流式调用阻塞（在途）→ dispose（cancel 后图执行停止，
     * 取消置位后子任务在途调用随令牌中止并抛取消异常，子任务批 superstep 无检查点）
     * → resume 只能恢复到 lead 检查点（nextNodeId=__PARALLEL__(lead)）→ lead 不重跑（结果已复用），
     * 子任务批作为执行缺口补跑（+2），聚合补跑（+1）。
     * stream 共 6 次 = 首跑（lead 1 + 子任务 2）+ 续跑（子任务 2 + 聚合 1）。
     */
    @Test
    void resume_afterLeadCheckpoint_reusesLeadResult() throws Exception {
        java.util.concurrent.CountDownLatch releaseSubtasks = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger contentCalls = new java.util.concurrent.atomic.AtomicInteger();
        when(clientRegistry.get(any())).thenReturn(chatClient);
        when(agentService.getAgentConfig(any())).thenReturn(java.util.Optional.empty());
        lenient().when(toolAssignments.forAgent(any())).thenReturn(ToolAssignments.ToolSet.EMPTY);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        // lead（第 1 次）立即返回拆解 JSON；子任务（第 2、3 次）在途阻塞到放行
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenAnswer(inv -> {
            if (contentCalls.incrementAndGet() == 1) {
                return Flux.just(fixedContent());
            }
            releaseSubtasks.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return Flux.just(fixedContent());
        });
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService, toolAssignments, null,
                new com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver());

        // lead 完成（检查点已落库）→ 子任务批在途阻塞中 → 模拟客户端断开
        reactor.core.Disposable disposable = agent
                .executeStreamReactive(new Goal("gr4", "调研竞品并输出报告"))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .subscribe();
        Thread.sleep(500);
        disposable.dispose();        // cancel → 置位取消令牌：在途子任务调用随令牌中止
        releaseSubtasks.countDown(); // 放行：子任务流的下一 token 边界中止（取消异常上抛）
        Thread.sleep(300);

        // 续跑：从 lead 检查点恢复 → lead 不重跑（复用拆解），子任务批补跑 + 聚合补跑
        java.util.List<String> resumed = agent
                .resumeStreamReactive(new Goal("gr4", "调研竞品并输出报告"))
                .collectList()
                .block();
        assertNotNull(resumed);
        assertEquals(fixedContent(), resumed.get(resumed.size() - 1), "续跑应以最终回答收尾");
        verify(requestSpec, org.mockito.Mockito.times(6)).stream();
    }

    /**
     * 客户端断连（cancel）→ doOnCancel 置位取消令牌 → 后续节点不再发起新的 LLM 调用：
     * lead 流式调用在途阻塞期间 dispose，释放后 lead 在 token 边界中止（取消异常上抛，
     * 图终止），除 lead 外不应有任何新调用（子任务/聚合全部短路）。
     * subscribeOn 模拟 AgentServiceImpl 的真实订阅方式（图执行与 cancel 分属不同线程）。
     */
    @Test
    void executeStreamReactive_cancelDuringLead_skipsRemainingLlmCalls() throws Exception {
        // 独立 stub：lead 的流式调用阻塞在闩锁上，模拟「调用进行中客户端断开」
        java.util.concurrent.CountDownLatch releaseLead = new java.util.concurrent.CountDownLatch(1);
        when(clientRegistry.get(any())).thenReturn(chatClient);
        when(agentService.getAgentConfig(any())).thenReturn(java.util.Optional.empty());
        lenient().when(toolAssignments.forAgent(any())).thenReturn(ToolAssignments.ToolSet.EMPTY);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        // lenient：短路生效时 lead 可能一次都不调用（UnnecessaryStubbing 免检）
        lenient().when(requestSpec.stream()).thenReturn(streamSpec);
        lenient().when(streamSpec.content()).thenAnswer(inv -> {
            releaseLead.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return Flux.just(fixedContent());
        });
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService, toolAssignments, null);

        // subscribeOn 让图执行离开测试线程（否则同步图驱动会把 subscribe() 卡在 lead 阻塞上）
        reactor.core.Disposable disposable = agent
                .executeStreamReactive(new Goal("g9", "调研竞品并输出报告"))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .subscribe();
        Thread.sleep(300);                    // 等图启动、lead 进入在途流式调用

        disposable.dispose();                 // 模拟客户端断开 → cancel → 置位取消令牌
        releaseLead.countDown();              // 供给恢复：lead 在下一个 token 边界中止
        Thread.sleep(500);                    // 给「短路失效则子任务会发起新调用」留暴露窗口

        // 时序兼容：lead 或在置位前已发起调用（1 次）、或被更早短路（0 次），但绝不能再多；
        // call 已统一流式背书，子任务/聚合的调用同样走 stream——断连后零新增
        verify(requestSpec, org.mockito.Mockito.atMost(1)).stream();
    }

    /**
     * 客户端断连中止在途聚合流式调用（Spec：在途 LLM 请求随断连中止）：
     * lead + 2 子任务正常完成 → 聚合首 token 已产出（在途）→ dispose 置位 →
     * 尾 token 供给恢复 → 聚合在下一个 token 边界中止（取消异常不可重试、图终止），
     * 不产生任何新增调用、部分输出不按成功收尾。
     */
    @Test
    void executeStreamReactive_cancelDuringAggregateStream_abortsInFlightCall() throws Exception {
        java.util.concurrent.CountDownLatch aggStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseTail = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger contentCalls = new java.util.concurrent.atomic.AtomicInteger();
        when(clientRegistry.get(any())).thenReturn(chatClient);
        when(agentService.getAgentConfig(any())).thenReturn(java.util.Optional.empty());
        lenient().when(toolAssignments.forAgent(any())).thenReturn(ToolAssignments.ToolSet.EMPTY);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenAnswer(inv -> {
            if (contentCalls.incrementAndGet() <= 3) {
                return Flux.just(fixedContent()); // lead + 2 子任务正常完成
            }
            // 聚合：首 token 已产出（在途），尾 token 等放行
            aggStarted.countDown();
            return Flux.concat(
                    Flux.just("首token"),
                    Flux.defer(() -> {
                        try {
                            releaseTail.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return Flux.just("尾token");
                    }));
        });
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService, toolAssignments, null);

        java.util.List<String> rows = new java.util.concurrent.CopyOnWriteArrayList<>();
        reactor.core.Disposable disposable = agent
                .executeStreamReactive(new Goal("g10", "调研竞品并输出报告"))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .subscribe(rows::add);
        assertTrue(aggStarted.await(10, java.util.concurrent.TimeUnit.SECONDS),
                "聚合应在超时前进入流式输出");

        disposable.dispose();     // 模拟客户端断开：取消置位，在途聚合调用随令牌中止
        releaseTail.countDown();  // 尾 token 供给恢复：验证在下一个 token 边界中止
        Thread.sleep(500);        // 留暴露窗口：若无中止/短路，会有新增调用

        // lead+2 子任务+聚合共 4 次调用，断连后零新增（取消异常不可重试、无后续节点）
        assertEquals(4, contentCalls.get(), "断连后不得发起新增 LLM 调用");
        // 部分输出不按成功返回：断连中止后收集到的帧不含完整最终回答
        assertTrue(rows.stream().noneMatch(fixedContent()::equals),
                "断连中止后不应产出完整最终回答, 实际帧序列: " + rows);
    }
}
