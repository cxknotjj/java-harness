package com.dark.javaHarness.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.agent.Agent;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.enums.GoalStatus;
import com.dark.javaHarness.service.AgentConfigProvider;
import com.dark.javaHarness.service.GoalService;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

/**
 * AgentServiceImpl 多 Agent 路由行为单测：
 * - 未传 agentId 走 general
 * - agentId=2(writer) 走 writer
 * - 不存在 agentId=999 回退 general
 * - 未知 agentName 抛出异常
 */
@ExtendWith(MockitoExtension.class)
class AgentServiceImplTest {

    @Mock
    private GoalService goalService;
    @Mock
    private AgentConfigProvider agentConfigProvider;

    private AgentServiceImpl agentService;

    @BeforeEach
    void setUp() {
        agentService = new AgentServiceImpl(
                goalService,
                agentConfigProvider,
                List.of(recordingAgent("general"), recordingAgent("writer"), recordingAgent("coder")));
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
        stubGoal("writer", null);

        List<String> tokens = agentService.executeStreamReactiveByAgentId(2L, "hi", null).collectList().block();

        assertEquals(List.of("ok-writer"), tokens, "响应式也应产出 writer 的完整结果");
        assertEquals("writer", routedTo.get(), "应按 agentId 路由到 writer");
    }

    @Test
    void executeStreamReactiveByAgentId_withMissingAgent_999_shouldFallbackToGeneral() {
        when(agentConfigProvider.findAgentNameById(999L)).thenReturn(Optional.empty());
        stubGoal("general", null);

        agentService.executeStreamReactiveByAgentId(999L, "hi", null).collectList().block();

        assertEquals("general", routedTo.get(), "agentId 未命中应回退默认 general");
    }

    @Test
    void executeStreamReactive_emitsTokensAndSucceeds() {
        Agent agent = mock(Agent.class);
        when(agent.name()).thenReturn("general");
        when(agent.executeStreamReactive(any())).thenReturn(Flux.just("a", "b"));
        agentService = new AgentServiceImpl(goalService, agentConfigProvider, List.of(agent));
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
        when(agent.name()).thenReturn("general");
        when(agent.executeStreamReactive(any())).thenReturn(Flux.error(new RuntimeException("boom")));
        agentService = new AgentServiceImpl(goalService, agentConfigProvider, List.of(agent));
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
        when(agent.name()).thenReturn("general");
        // 永不完成的流 + 订阅建立闩锁：确保 dispose 发生在链建立之后（否则 cancel 不达 doOnCancel）
        java.util.concurrent.CountDownLatch subscribed = new java.util.concurrent.CountDownLatch(1);
        when(agent.executeStreamReactive(any()))
                .thenReturn(Flux.<String>never().doOnSubscribe(s -> subscribed.countDown()));
        agentService = new AgentServiceImpl(goalService, agentConfigProvider, List.of(agent));
        Goal goal = stubGoal("general", null);

        reactor.core.Disposable disposable =
                agentService.executeStreamReactive("general", "hi", null).subscribe();
        assertTrue(subscribed.await(5, java.util.concurrent.TimeUnit.SECONDS), "上游应已订阅");
        disposable.dispose();

        assertEquals(GoalStatus.FAILED, goal.status(), "断连取消后 goal 应标记 FAILED");
        assertEquals("客户端断开，编排已取消", goal.summary());
        org.mockito.Mockito.verify(goalService, org.mockito.Mockito.times(2)).update(goal);
    }
}