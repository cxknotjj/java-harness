package com.dark.javaHarness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.service.AgentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;

/**
 * MultiAgentGraphAgent 单测：
 * - 能构建 StateGraph 并执行（lead 拆解 → 并行子任务 → 聚合）
 * - lead 返回合法 JSON 时，execute() 返回最终回答（非空）
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
    private AgentService agentService;

    private MultiAgentGraphAgent agent;

    /** lead 返回两条子任务，子任务/聚合返回固定文本 */
    private String fixedContent() {
        return "{\"subtasks\":[\"子任务A\",\"子任务B\"]}";
    }

    private void stubChat(String content) {
        when(clientRegistry.get(any())).thenReturn(chatClient);
        when(agentService.getAgentConfig(anyString())).thenReturn(java.util.Optional.empty());
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(content);
    }

    @Test
    void execute_shouldBuildGraphAndReturnFinalAnswer() {
        stubChat(fixedContent());
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService);

        Goal goal = new Goal("g1", "调研竞品并输出报告");
        String reply = agent.execute(goal);

        assertNotNull(reply);
        assertFalse(reply.isBlank(), "execute 应产出最终回答");
    }

    @Test
    void execute_returnsNonEmptyForAggregate() {
        // 所有节点统一返回固定 content；lead 解析出 subtasks，聚合把该 content 作为最终回答
        stubChat(fixedContent());
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService);

        String reply = agent.execute(new Goal("g2", "hello"));

        assertNotNull(reply);
        assertEquals(fixedContent(), reply, "聚合节点把读取到的结果作为最终回答返回");
    }

    /** executeStreamReactive 应同时发射：进度行（ProgressLine.MARK 前缀）+ 内容行（最终回答） */
    @Test
    void executeStreamReactive_emitsProgressRowsAndContent() {
        stubChat(fixedContent());
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService);

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
        assertFalse(progressCount == 0, "应至少发射一条进度行");
        assertFalse(contentCount == 0, "应至少发射一条内容行（最终回答）");
    }

    /** 解析后的输出行视图：stage 为进度阶段（内容行为 null） */
    private record ParsedRow(String stage, boolean content) { }

    /**
     * 基于原生 graph.stream() 的阶段时序：
     * 首帧=「编排」→ 存在「拆解」→ 子任务若干（拆解之后）→「聚合」→ 最终内容行收尾。
     */
    @Test
    void executeStreamReactive_streamsStageEventsInOrder() {
        stubChat(fixedContent());
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService);

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

        // 3. 至少 2 条「子任务」完成事件（生命周期钩子 after(subtask-N) 补齐并行分支），且都位于最终内容行之前
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

    private static int indexOfStage(java.util.List<ParsedRow> rows, String stage) {
        for (int i = 0; i < rows.size(); i++) {
            if (stage.equals(rows.get(i).stage())) {
                return i;
            }
        }
        return -1;
    }

    /* ---------------- 专家 agent 派遣 ---------------- */

    /** 构建一个独立的、完整 stub 链的 ChatClient mock（各专家客户端互不干扰）。 */
    private ChatClient newStubbedClient(String content) {
        ChatClient client = mock(ChatClient.class);
        ChatClientRequestSpec req = mock(ChatClientRequestSpec.class);
        CallResponseSpec resp = mock(CallResponseSpec.class);
        when(client.prompt()).thenReturn(req);
        when(req.system(anyString())).thenReturn(req);
        when(req.user(anyString())).thenReturn(req);
        when(req.options(any())).thenReturn(req);
        when(req.call()).thenReturn(resp);
        when(resp.content()).thenReturn(content);
        return client;
    }

    /** lead 返回新格式（对象数组带 agent 指派），专家行登记了 model 配置。 */
    private void stubExpertDispatch() {
        String leadJson =
                "{\"subtasks\":[{\"desc\":\"调研竞品\",\"agent\":\"researcher\"},"
                        + "{\"desc\":\"统计销量\",\"agent\":\"analyst\"}]}";
        stubChat(leadJson); // 默认客户端：lead + 聚合（get(any()) 兜底）
        // 专家行有配置 → 子任务按专家 model 取对应客户端
        when(agentService.getAgentConfig(eq("researcher")))
                .thenReturn(java.util.Optional.of(new com.dark.javaHarness.domain.AgentConfig("qwen-plus", "调研提示词")));
        when(agentService.getAgentConfig(eq("analyst")))
                .thenReturn(java.util.Optional.of(new com.dark.javaHarness.domain.AgentConfig("deepseek-chat", "分析提示词")));
        // 先建好独立 stub 的专家客户端，再注册（避免在 when() 求值内嵌套 stubbing 触发 UnfinishedStubbing）
        ChatClient researcherClient = newStubbedClient("调研结果");
        ChatClient analystClient = newStubbedClient("分析结果");
        when(clientRegistry.get(eq("qwen-plus"))).thenReturn(researcherClient);
        when(clientRegistry.get(eq("deepseek-chat"))).thenReturn(analystClient);
    }

    @Test
    void execute_dispatchesSubtasksToExpertClients() {
        stubExpertDispatch();
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService);

        String reply = agent.execute(new Goal("g5", "调研竞品并统计销量"));

        assertNotNull(reply);
        assertFalse(reply.isBlank(), "专家派遣后仍应产出聚合最终回答");
        // 子任务按指派查询专家配置并取对应模型客户端
        verify(agentService).getAgentConfig("researcher");
        verify(agentService).getAgentConfig("analyst");
        verify(clientRegistry).get("qwen-plus");
        verify(clientRegistry).get("deepseek-chat");
    }

    @Test
    void execute_rejectsUnknownAgentAndFallsBackToDefault() {
        String leadJson = "{\"subtasks\":[{\"desc\":\"任务X\",\"agent\":\"hacker\"}]}";
        stubChat(leadJson);
        agent = new MultiAgentGraphAgent("multi-agent", clientRegistry, agentService);

        String reply = agent.execute(new Goal("g6", "任务X"));

        assertNotNull(reply);
        // 白名单拒绝：绝不以非法名查配置/取客户端
        verify(agentService, never()).getAgentConfig("hacker");
        verify(clientRegistry, never()).get("hacker");
    }
}