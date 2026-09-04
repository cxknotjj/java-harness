package com.dark.javaHarness.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.config.ContextBudgetProperties;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.service.AgentConfigProvider;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.tool.ToolAssignments;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * AgentRegistry 单测：
 * - 启动注册：agent 表 is_internal=0 行全部注册为可路由 GA 实例
 * - 内部角色排除：listAgentNames 只返回 is_internal=0 行名（排除逻辑由 provider 按 is_internal 字段驱动）
 * - 脏数据 fail-safe：单行构造异常 warn 跳过，其余照常注册，init 不抛
 * - general 兜底：表无 general 行时代码兜底注册；DB 行配置优先由 GA 内部按名查表保证（注册表不分叉）
 * - 惰性注册：未命中查表注册并缓存；未命中/内部角色抛「未知 Agent」含可用列表
 * - computeIfAbsent 并发：并发同名请求仅构造一次
 * - register 预注入：multi-agent 编排 bean 注册后可路由命中（同名覆盖）
 */
@ExtendWith(MockitoExtension.class)
class AgentRegistryTest {

    @Mock
    private AgentConfigProvider provider;
    @Mock
    private ObjectProvider<AgentService> agentServiceProvider;
    @Mock
    private ChatClientRegistry clientRegistry;
    @Mock
    private SessionService memoryStore;
    @Mock
    private ToolAssignments toolAssignments;
    @Mock
    private AgentService agentService;

    private AgentRegistry newRegistry() {
        // recorder/lazyTools 传 null：GA 支持无观测/禁用延迟加载构造（单测场景）
        return new AgentRegistry(provider, agentServiceProvider, clientRegistry, memoryStore,
                toolAssignments, null, new ContextBudgetProperties(), null);
    }

    @Test
    void init_shouldRegisterAllRoutableRows() {
        when(provider.listAgentNames()).thenReturn(List.of("general", "deepseek", "nailong"));
        when(agentServiceProvider.getObject()).thenReturn(agentService);
        AgentRegistry registry = newRegistry();

        registry.init();

        assertEquals(Set.of("general", "deepseek", "nailong"), registry.agentNames(), "表行应全部注册");
        assertEquals("nailong", registry.require("nailong").name(), "行名即 Agent name");
    }

    @Test
    void init_shouldNotRegisterInternalRoles() {
        // listAgentNames 仅返回 is_internal=0 行名：lead/aggregator/multi-agent（is_internal=1）已被 provider 过滤
        when(provider.listAgentNames()).thenReturn(List.of("general", "deepseek"));
        when(agentServiceProvider.getObject()).thenReturn(agentService);
        AgentRegistry registry = newRegistry();

        registry.init();

        assertFalse(registry.agentNames().contains("lead"), "内部角色 lead 不应注册");
        assertFalse(registry.agentNames().contains("aggregator"), "内部角色 aggregator 不应注册");
        assertFalse(registry.agentNames().contains("multi-agent"), "multi-agent 编排 bean 由预注入注册");
    }

    @Test
    void init_dirtyRow_shouldSkipAndContinueWithoutThrow() {
        when(provider.listAgentNames()).thenReturn(List.of("bad", "general"));
        // 第一次构造（bad 行）抛异常模拟脏数据，后续构造正常
        AtomicInteger constructions = new AtomicInteger();
        when(agentServiceProvider.getObject()).thenAnswer(inv -> {
            if (constructions.incrementAndGet() == 1) {
                throw new IllegalStateException("模拟构造失败");
            }
            return agentService;
        });
        AgentRegistry registry = newRegistry();

        assertDoesNotThrow(registry::init, "单行脏数据不应阻断启动注册");

        assertFalse(registry.agentNames().contains("bad"), "脏行应被跳过");
        assertEquals("general", registry.require("general").name(), "其余行应照常注册");
    }

    @Test
    void init_generalRowMissing_shouldRegisterCodeFallback() {
        when(provider.listAgentNames()).thenReturn(List.of("deepseek"));
        when(agentServiceProvider.getObject()).thenReturn(agentService);
        AgentRegistry registry = newRegistry();

        registry.init();

        assertTrue(registry.agentNames().contains("general"), "general 缺行应用代码兜底注册");
        assertEquals("general", registry.require("general").name(), "兜底后 general 应可路由命中");
        // DB 行优先语义：GA 实例每次调用按 agentName 查 agent 表——general 行存在时 DB 配置生效、
        // 缺失时走内置默认 prompt + 默认模型，两种来源不合并；注册表注册的是同一个 GA 构造路径，无需分叉。
    }

    @Test
    void require_miss_shouldLazilyRegisterAndCache() {
        when(provider.listAgentNames()).thenReturn(List.of("nailong"));
        when(agentServiceProvider.getObject()).thenReturn(agentService);
        AgentRegistry registry = newRegistry();

        Agent first = registry.require("nailong");
        Agent second = registry.require("nailong");

        assertEquals("nailong", first.name());
        assertSame(first, second, "再次 require 应返回缓存同实例");
        assertTrue(registry.agentNames().contains("nailong"), "惰性注册后应进入路由表");
    }

    @Test
    void require_unknownName_shouldThrowWithAvailableList() {
        when(provider.listAgentNames()).thenReturn(List.of("general"));
        when(agentServiceProvider.getObject()).thenReturn(agentService);
        AgentRegistry registry = newRegistry();
        registry.init();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> registry.require("ghost"));

        assertTrue(ex.getMessage().contains("未知 Agent"), "应抛「未知 Agent」文案");
        assertTrue(ex.getMessage().contains("ghost"));
        assertTrue(ex.getMessage().contains("general"), "错误文案应含可用列表");
    }

    @Test
    void require_internalRole_shouldThrowAsUnknown() {
        // lead 行 is_internal=1，不在 listAgentNames 返回名单中 → 按未命中处理，不惰性注册
        when(provider.listAgentNames()).thenReturn(List.of("general"));
        AgentRegistry registry = newRegistry();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> registry.require("lead"));

        assertTrue(ex.getMessage().contains("未知 Agent"));
        assertFalse(registry.agentNames().contains("lead"), "内部角色不应被惰性注册");
    }

    @Test
    void require_concurrentSameName_shouldConstructOnlyOnce() throws Exception {
        when(provider.listAgentNames()).thenReturn(List.of("nailong"));
        AtomicInteger constructions = new AtomicInteger();
        when(agentServiceProvider.getObject()).thenAnswer(inv -> {
            constructions.incrementAndGet();
            return agentService;
        });
        AgentRegistry registry = newRegistry();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Callable<Agent> task = () -> {
                barrier.await();
                return registry.require("nailong");
            };
            Future<Agent> f1 = pool.submit(task);
            Future<Agent> f2 = pool.submit(task);
            Agent a1 = f1.get(5, TimeUnit.SECONDS);
            Agent a2 = f2.get(5, TimeUnit.SECONDS);

            assertSame(a1, a2, "并发同名请求应拿到同一实例");
            assertEquals(1, constructions.get(), "computeIfAbsent 应保证仅构造一次");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void register_preInjectedMultiAgent_shouldBeRoutableAndOverwrite() {
        Agent multiAgent = stubAgent("multi-agent");
        AgentRegistry registry = newRegistry();

        registry.register(multiAgent);
        assertSame(multiAgent, registry.require("multi-agent"), "预注入编排 bean 应可路由命中");

        Agent replacement = stubAgent("multi-agent");
        registry.register(replacement);
        assertSame(replacement, registry.require("multi-agent"), "同名重复注册按覆盖处理");
    }

    /** 最小 Agent stub：仅验证路由命中与 name 透传 */
    private Agent stubAgent(String name) {
        return new Agent() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String execute(Goal goal) {
                return "ok-" + name;
            }
        };
    }
}
