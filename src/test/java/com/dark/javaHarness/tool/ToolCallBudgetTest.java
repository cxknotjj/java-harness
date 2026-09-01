package com.dark.javaHarness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * ToolCallBudget（工具调用硬预算）单测：
 * - 上限内正常执行并透传结果
 * - 超限后不再执行真实工具，返回引导文本
 * - 计数跨多个工具共享（单次调用总预算）
 * - 空列表原样返回
 */
class ToolCallBudgetTest {

    /** 计数桩工具：记录真实执行次数 */
    private static class CountingTool implements ToolCallback {
        int executions;

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name("t").description("d").inputSchema("{}").build();
        }

        @Override
        public String call(String toolInput) {
            executions++;
            return "ok#" + executions;
        }
    }

    @Test
    void withinBudget_executesNormally() {
        CountingTool tool = new CountingTool();
        ToolCallback wrapped = ToolCallBudget.limit(List.of(tool), 3).get(0);

        assertEquals("ok#1", wrapped.call("a"));
        assertEquals("ok#2", wrapped.call("b"));
        assertEquals(2, tool.executions);
    }

    @Test
    void overBudget_returnsGuidance_withoutRealExecution() {
        CountingTool tool = new CountingTool();
        ToolCallback wrapped = ToolCallBudget.limit(List.of(tool), 2).get(0);

        wrapped.call("1");
        wrapped.call("2");
        String third = wrapped.call("3");
        assertEquals(ToolCallBudget.EXHAUSTED_MESSAGE, third, "超限后应返回引导文本");
        assertEquals(2, tool.executions, "超限调用不应执行真实工具");
    }

    @Test
    void counterSharedAcrossTools_totalBudget() {
        CountingTool t1 = new CountingTool();
        CountingTool t2 = new CountingTool();
        List<ToolCallback> wrapped = ToolCallBudget.limit(List.of(t1, t2), 3);

        wrapped.get(0).call("a");
        wrapped.get(0).call("b");
        wrapped.get(1).call("c");
        String fourth = wrapped.get(1).call("d");

        assertEquals(ToolCallBudget.EXHAUSTED_MESSAGE, fourth, "预算跨工具共享：第 4 次应被拦截");
        assertEquals(2, t1.executions);
        assertEquals(1, t2.executions);
    }

    @Test
    void emptyOrNullList_returnedAsIs() {
        assertTrue(ToolCallBudget.limit(List.of(), 5).isEmpty());
        assertSame(null, ToolCallBudget.limit(null, 5));
    }
}
