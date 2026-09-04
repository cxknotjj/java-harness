package com.dark.javaHarness.prompt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * MemoryPolicy 单测：
 * - 角色策略：lead / general 注入，aggregator 与子任务专家（researcher/coder/analyst/writer）不注入
 * - 可注入性（编排调用器口径）：仅 lead + 有效会话 ID；空会话 ID 一律不注入；
 *   编排内 general 属子任务兜底专家身份，不注入
 */
class MemoryPolicyTest {

    private final MemoryPolicy policy = new MemoryPolicy();

    @Test
    void lead_shouldInject() {
        assertTrue(policy.shouldInject("lead"), "编排拆解角色 lead 应注入会话记忆");
    }

    @Test
    void general_shouldInject() {
        assertTrue(policy.shouldInject("general"), "简单路径 general 属记忆角色（路径 A 现状装配）");
    }

    @Test
    void aggregator_shouldNotInject() {
        assertFalse(policy.shouldInject("aggregator"), "聚合节点忠实于各子任务结果，不注入记忆");
    }

    @Test
    void subtaskExperts_shouldNotInject() {
        for (String expert : List.of("researcher", "coder", "analyst", "writer")) {
            assertFalse(policy.shouldInject(expert), expert + " 是子任务专家，不应注入记忆");
        }
    }

    @Test
    void nullOrUnknownAgent_shouldNotInject() {
        assertFalse(policy.shouldInject(null));
        assertFalse(policy.shouldInject("multi-agent"));
    }

    @Test
    void leadWithValidSession_shouldInjectForCaller() {
        assertTrue(policy.shouldInject("lead", "sess-1"));
    }

    @Test
    void blankSessionId_shouldNotInject() {
        assertFalse(policy.shouldInject("lead", null), "无会话 ID 场景（路由判定等）不注入");
        assertFalse(policy.shouldInject("lead", ""));
        assertFalse(policy.shouldInject("lead", "   "));
    }

    @Test
    void nonLead_shouldNotInjectForCaller_evenWithSession() {
        // 编排调用器只认 lead：编排内 general 是子任务兜底专家身份（非路径 A 会话主角色），
        // 子任务上下文由 lead 在子任务描述中传递
        assertFalse(policy.shouldInject("general", "sess-1"));
        assertFalse(policy.shouldInject("aggregator", "sess-1"));
        assertFalse(policy.shouldInject("researcher", "sess-1"));
    }
}
