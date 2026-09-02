package com.dark.javaHarness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** 返回固定内容的桩工具（token 预算用例） */
    private static class FixedTool implements ToolCallback {
        private final String output;
        int executions;

        FixedTool(String output) {
            this.output = output;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name("f").description("d").inputSchema("{}").build();
        }

        @Override
        public String call(String toolInput) {
            executions++;
            return output;
        }
    }

    @Test
    void toolOutputOverTokenBudget_truncatedToRemaining() {
        // 6000 个 ascii 字符 ≈ 1500 token；预算 1000 → 截断到 ≤1000 token
        FixedTool tool = new FixedTool("x".repeat(6000));
        ToolCallback wrapped = ToolCallBudget.limit(List.of(tool), 5, 1000).get(0);

        String out = wrapped.call("a");

        assertEquals(1, tool.executions, "真实工具仍被执行，只是输出被裁剪");
        assertEquals(1000, TokenEstimator.estimateTokens(out), "截断后应恰好落在预算内");
        assertTrue(out.endsWith(ToolCallBudget.TRUNCATED_SUFFIX), "截断应带标记");
    }

    @Test
    void contextBudgetExhausted_laterToolsReturnGuidance() {
        FixedTool big1 = new FixedTool("字".repeat(800)); // 800 token
        FixedTool big2 = new FixedTool("字".repeat(800)); // 剩 200 → 裁到 200，预算耗尽
        FixedTool third = new FixedTool("ok");
        List<ToolCallback> wrapped = ToolCallBudget.limit(List.of(big1, big2, third), 5, 1000);

        wrapped.get(0).call("a");
        wrapped.get(1).call("b");
        String out = wrapped.get(2).call("c");

        assertEquals(ToolCallBudget.CONTEXT_EXHAUSTED_MESSAGE, out, "预算耗尽后应返回引导文本");
        assertEquals(0, third.executions, "预算耗尽后不应再执行真实工具");
    }

    @Test
    void contextBudgetSecondCall_trimmedToRemaining_notBlocked() {
        FixedTool big = new FixedTool("字".repeat(800));
        FixedTool small = new FixedTool("y".repeat(4000)); // ≈1000 token，剩余只有 200
        List<ToolCallback> wrapped = ToolCallBudget.limit(List.of(big, small), 5, 1000);

        wrapped.get(0).call("a"); // 用掉 800，剩 200
        String second = wrapped.get(1).call("b");

        assertEquals(1, small.executions, "预算未耗尽前真实工具仍执行");
        assertEquals(200, TokenEstimator.estimateTokens(second), "结果应裁剪到剩余预算 200");
    }

    @Test
    void contextBudgetWithinLimit_passThroughIntact() {
        FixedTool tool = new FixedTool("短结果");
        ToolCallback wrapped = ToolCallBudget.limit(List.of(tool), 5, 5000).get(0);

        assertEquals("短结果", wrapped.call("a"), "预算内结果原样透传，不做任何裁剪");
    }
}
