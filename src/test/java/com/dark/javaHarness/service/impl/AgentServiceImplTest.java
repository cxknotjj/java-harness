package com.dark.javaHarness.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.agent.Agent;
import com.dark.javaHarness.domain.Goal;
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
    void executeStreamByAgentId_withWriterAgent_two() {
        // 显式提供同签名的 executeStreamByAgentId(2) → writer
        when(agentConfigProvider.findAgentNameById(2L)).thenReturn(Optional.of("writer"));
        stubGoal("writer", null);

        Goal goal = agentService.executeStreamByAgentId(2L, "hi", null, ignored -> { });

        assertEquals("writer", routedTo.get());
    }

    @Test
    void executeStreamByAgentId_withMissingAgent_999_shouldFallbackToGeneral() {
        // agentId=999 无记录 → 回退默认 general
        when(agentConfigProvider.findAgentNameById(999L)).thenReturn(Optional.empty());
        stubGoal("general", null);

        agentService.executeStreamByAgentId(999L, "hi", null, ignored -> { });

        assertEquals("general", routedTo.get());
    }

    @Test
    void executeStreamByAgentId_withNullAgent_shouldFallbackToGeneral() {
        // 不传 agentId → 走 general
        when(agentConfigProvider.findAgentNameById(eq(null))).thenReturn(Optional.empty());
        stubGoal("general", null);

        agentService.executeStreamByAgentId(null, "hi", null, ignored -> { });

        assertEquals("general", routedTo.get());
    }

    @Test
    void executeStream_withUnknownAgentName_shouldThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> agentService.executeStream("caracal-agent", "hi", null, ignored -> { }));
    }
}