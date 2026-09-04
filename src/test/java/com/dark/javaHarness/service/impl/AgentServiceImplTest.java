package com.dark.javaHarness.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.agent.Agent;
import com.dark.javaHarness.agent.AgentRegistry;
import com.dark.javaHarness.agent.GeneralAssistantAgent;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.enums.GoalStatus;
import com.dark.javaHarness.service.AgentConfigProvider;
import com.dark.javaHarness.service.GoalService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

/**
 * AgentServiceImpl 多 Agent 路由行为单测（路由完全委托 AgentRegistry 表驱动注册表）：
 * - agentId=2(writer) 走 writer
 * - 不存在 agentId=999 回退 general
 * - 未知 agentName 透传注册表的「未知 Agent」异常，且不创建 goal
 * - agentNames 委托 registry 动态路由表
 * - 表行 Agent（deepseek/nailong）经 registry 返回实例（GA 实例/惰性注册）后正常路由
 * - 流式全链路生命周期（成功/失败/断连取消）不回归
 */
@ExtendWith(MockitoExtension.class)
class AgentServiceImplTest {

    @Mock
    private GoalService goalService;
    @Mock
    private AgentConfigProvider agentConfigProvider;
    @Mock
    private AgentRegistry agentRegistry;

    private AgentServiceImpl agentService;

    @BeforeEach
    void setUp() {
        agentService = new AgentServiceImpl(goalService, agentConfigProvider, agentRegistry);
    }

    /** 创建能记录 "被路由到哪个 agent" 的 stub Agent：executeStream/execute 时把 name 记入 AtomicReference */
    private Agent recordingAgent(String name) {
        return new Agent() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String execute(Goal goal) {
                routedTo.set(name);
                return "ok-" + name;
            }

            @Override
            public void executeStream(Goal goal, Consumer<String> onToken) {
                routedTo.set(name);
                onToken.accept("hello");
                onToken.accept(" world");
            }
        };
    }

    private final AtomicReference<String> routedTo = new AtomicReference<>();

    private Goal stubGoal(String agentName, String sessionId) {
        Goal g = new Goal("goal-x", "hi", sessionId);
        when(goalService.create(any(), any())).thenReturn(g);
        return g;
    }

    @Test
    void executeStreamReactiveByAgentId_withWriterAgent_routesToWriter() {
        when(agentConfigProvider.findAgentNameById(2L)).thenReturn(Optional.of("writer"));
        when(agentRegistry.require("writer")).thenReturn(recordingAgent("writer"));
        stubGoal("writer", null);

        List<String> tokens = agentService.executeStreamReactiveByAgentId(2L, "hi", null).collectList().block();

        assertEquals(List.of("ok-writer"), tokens, "响应式也应产出 writer 的完整结果");
        assertEquals("writer", routedTo.get(), "应按 agentId 路由到 writer");
    }

    @Test
    void executeStreamReactiveByAgentId_withMissingAgent_999_shouldFallbackToGeneral() {
        when(agentConfigProvider.findAgentNameById(999L)).thenReturn(Optional.empty());
        when(agentRegistry.require("general")).thenReturn(recordingAgent("general"));
        stubGoal("general", null);

        agentService.executeStreamReactiveByAgentId(999L, "hi", null).collectList().block();

        assertEquals("general", routedTo.get(), "agentId 未命中应回退默认 general");
    }

    @Test
    void executeStreamReactive_emitsTokensAndSucceeds() {
        Agent agent = mock(Agent.class);
        when(agent.executeStreamReactive(any())).thenReturn(Flux.just("a", "b"));
        when(agentRegistry.require("general")).thenReturn(agent);
        Goal goal = stubGoal("general", null);

        List<String> tokens = agentService.executeStreamReactive("general", "hi", null)
                .collectList().block();

        assertEquals(List.of("a", "b"), tokens);
        assertEquals(GoalStatus.SUCCEEDED, goal.status());
        assertEquals("ab", goal.summary());
    }

    @Test
    void executeStreamReactive_onError_marksFailed() {
        Agent agent = mock(Agent.class);
        when(agent.executeStreamReactive(any())).thenReturn(Flux.error(new RuntimeException("boom")));
        when(agentRegistry.require("general")).thenReturn(agent);
        Goal goal = stubGoal("general", null);

        assertThrows(RuntimeException.class,
                () -> agentService.executeStreamReactive("general", "hi", null).collectList().block());

        assertEquals(GoalStatus.FAILED, goal.status());
        assertEquals("boom", goal.summary());
    }

    /** 客户端断开 → Reactor cancel：goal 应落地为 FAILED（客户端断开），不再残留 RUNNING */
    @Test
    void executeStreamReactive_onCancel_marksFailedAndPersists() throws Exception {
        Agent agent = mock(Agent.class);
        // 永不完成的流 + 订阅建立闩锁：确保 dispose 发生在链建立之后（否则 cancel 不达 doOnCancel）
        java.util.concurrent.CountDownLatch subscribed = new java.util.concurrent.CountDownLatch(1);
        when(agent.executeStreamReactive(any()))
                .thenReturn(Flux.<String>never().doOnSubscribe(s -> subscribed.countDown()));
        when(agentRegistry.require("general")).thenReturn(agent);
        Goal goal = stubGoal("general", null);

        reactor.core.Disposable disposable =
                agentService.executeStreamReactive("general", "hi", null).subscribe();
        assertTrue(subscribed.await(5, java.util.concurrent.TimeUnit.SECONDS), "上游应已订阅");
        disposable.dispose();

        assertEquals(GoalStatus.FAILED, goal.status(), "断连取消后 goal 应标记 FAILED");
        assertEquals("客户端断开，编排已取消", goal.summary());
        org.mockito.Mockito.verify(goalService, org.mockito.Mockito.times(2)).update(goal);
    }

    /* ---------------- resumeStreamReactive（断点续跑） ---------------- */

    /** 续跑固定路由到 multi-agent（registry 预注入的编排 bean），复用传入 goal，生命周期照常回写 SUCCEEDED */
    @Test
    void resumeStreamReactive_routesToMultiAgentAndReusesGoal() {
        when(agentRegistry.require("multi-agent")).thenReturn(recordingAgent("multi-agent"));

        Goal goal = new Goal("goal-resume", "复杂任务", "s1");
        java.util.List<String> tokens = agentService.resumeStreamReactive(goal).collectList().block();

        // 流首为 goal 进度行（goalId 尽早下发供 CLI /resume 记录），随后是 agent 输出
        assertEquals(java.util.List.of(
                com.dark.javaHarness.agent.ProgressLine.encode("goal", "goal-resume"),
                "ok-multi-agent"), tokens);
        assertEquals("multi-agent", routedTo.get(), "续跑应固定路由 multi-agent");
        assertEquals(GoalStatus.SUCCEEDED, goal.status(), "续跑成功后 goal 应标记 SUCCEEDED");
        // 复用传入 goal：不新建 goal（仅 markRunning + 完成共 2 次 update）
        org.mockito.Mockito.verify(goalService, org.mockito.Mockito.never())
                .create(any(), any());
        org.mockito.Mockito.verify(goalService, org.mockito.Mockito.times(2)).update(goal);
    }

    /* ---------------- goal 进度行（goalId 尽早下发） ---------------- */

    /** 编排路径（multi-agent）流首下发 goal 进度行：CLI 断开前也能记录 goalId 供 /resume */
    @Test
    void executeStreamReactive_multiAgent_emitsGoalProgressFirst() {
        when(agentRegistry.require("multi-agent")).thenReturn(recordingAgent("multi-agent"));
        Goal goal = stubGoal("multi-agent", "s1");

        java.util.List<String> tokens = agentService.executeStreamReactive("multi-agent", "复杂任务", "s1")
                .collectList().block();

        assertEquals(com.dark.javaHarness.agent.ProgressLine.encode("goal", "goal-x"), tokens.get(0),
                "编排流首应为 goal 进度行");
        assertEquals("ok-multi-agent", tokens.get(tokens.size() - 1));
        assertEquals(goal.id(), "goal-x");
    }

    /** 简单路径（general）不发 goal 进度行：普通聊天无可续跑检查点，不产生续跑目标 */
    @Test
    void executeStreamReactive_general_noGoalProgress() {
        when(agentRegistry.require("general")).thenReturn(recordingAgent("general"));
        stubGoal("general", null);

        java.util.List<String> tokens = agentService.executeStreamReactive("general", "hi", null)
                .collectList().block();

        assertEquals(java.util.List.of("ok-general"), tokens, "简单路径不应有 goal 进度行");
        tokens.forEach(t -> org.junit.jupiter.api.Assertions.assertFalse(
                com.dark.javaHarness.agent.ProgressLine.isProgress(t), "不应含任何进度行"));
    }

    /* ---------------- 路由委托 AgentRegistry（表驱动） ---------------- */

    /** 未知 agentName：透传 AgentRegistry 的「未知 Agent」异常，且路由失败发生在业务前（不创建 goal） */
    @Test
    void executeSync_unknownAgentName_propagatesRegistryError() {
        when(agentRegistry.require("ghost"))
                .thenThrow(new IllegalArgumentException("未知 Agent: ghost，可用: [general]"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> agentService.executeSync("ghost", "hi"));

        assertTrue(ex.getMessage().contains("未知 Agent"), "应透传注册表的未知 Agent 文案");
        org.mockito.Mockito.verify(goalService, org.mockito.Mockito.never()).create(anyString(), anyString());
    }

    /** agentNames 委托 registry 动态路由表：表驱动注册结果即 /agent 可切换列表 */
    @Test
    void agentNames_delegatesToRegistry() {
        when(agentRegistry.agentNames()).thenReturn(Set.of("general", "deepseek", "nailong"));

        assertEquals(Set.of("general", "deepseek", "nailong"), agentService.agentNames());
    }

    /** registry 返回表驱动构造的 GA 实例时正常路由：表行（deepseek）按行名命中并执行 */
    @Test
    void executeStreamReactive_tableRowGaInstance_routesNormally() {
        GeneralAssistantAgent ga = mock(GeneralAssistantAgent.class);
        when(ga.executeStreamReactive(any())).thenReturn(Flux.just("ga-token"));
        when(agentRegistry.require("deepseek")).thenReturn(ga);
        stubGoal("deepseek", null);

        List<String> tokens = agentService.executeStreamReactive("deepseek", "hi", null).collectList().block();

        assertEquals(List.of("ga-token"), tokens, "registry 返回的 GA 实例应按表行名正常路由执行");
    }

    /** 运行时新插表行（nailong）：registry 惰性注册后 require 返回实例，服务层无改动即路由成功 */
    @Test
    void executeStreamReactive_newTableRowAgent_nailong_routesAfterLazyRegistration() {
        when(agentRegistry.require("nailong")).thenReturn(recordingAgent("nailong"));
        stubGoal("nailong", null);

        List<String> tokens = agentService.executeStreamReactive("nailong", "hi", null).collectList().block();

        assertEquals(List.of("ok-nailong"), tokens, "运行时插行经惰性注册后应可路由");
        assertEquals("nailong", routedTo.get());
    }
}
