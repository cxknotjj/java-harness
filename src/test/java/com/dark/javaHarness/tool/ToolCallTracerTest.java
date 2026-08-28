package com.dark.javaHarness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.agent.ProgressLine;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * ToolCallTracer 单测：
 * - 事件组装（起始/结果 detail、参数摘要、diff 行数摘要）——纯函数
 * - 装饰行为：call 前后发 tool / tool-done 事件、失败发 ✗ 且异常原样抛出
 * - emitter 为 null 时零开销直通；@Tool 注解对象可转装饰回调
 */
class ToolCallTracerTest {

    // ---- 事件组装：参数摘要 ----

    @Test
    void argSummary_prefersCandidateKey() {
        String input = "{\"path\":\"/tmp/a.py\",\"content\":\"ignored\"}";
        assertEquals("/tmp/a.py", ToolCallTracer.argSummary(input));
    }

    @Test
    void argSummary_fallsBackToTruncatedRawJson() {
        String input = "{\"unknown_key\":\"v\",\"n\":1}";
        assertEquals(input, ToolCallTracer.argSummary(input));
    }

    @Test
    void argSummary_truncatesLongValues() {
        String input = "{\"url\":\"https://example.com/" + "x".repeat(100) + "\"}";
        String r = ToolCallTracer.argSummary(input);
        assertTrue(r.length() <= 61, "摘要应截断到 60 字符+省略号: " + r);
        assertTrue(r.endsWith("…"), "截断应以省略号结尾: " + r);
    }

    @Test
    void argSummary_nonJsonInputReturnsRawTruncated() {
        assertEquals("plain text", ToolCallTracer.argSummary("plain text"));
        assertEquals("", ToolCallTracer.argSummary(null));
    }

    // ---- 事件组装：diff 摘要 ----

    @Test
    void diffSummary_oldAndNewCountsLines() {
        String input = "{\"old_string\":\"a\\nb\\nc\",\"new_string\":\"a\\nb2\\nc\\nd\"}";
        assertEquals("+4/-3 行", ToolCallTracer.diffSummary(input));
    }

    @Test
    void diffSummary_newOnlyCountsPlus() {
        String input = "{\"content\":\"x\\ny\"}";
        assertEquals("+2 行", ToolCallTracer.diffSummary(input));
    }

    @Test
    void diffSummary_noWritableFieldsReturnsEmpty() {
        assertEquals("", ToolCallTracer.diffSummary("{\"path\":\"/tmp/a.py\"}"));
        assertEquals("", ToolCallTracer.diffSummary(null));
    }

    // ---- 事件组装：起止 detail 整体格式 ----

    @Test
    void startDetail_wrapsNameWithArgs() {
        assertEquals("FetchUrl(https://a.com)",
                ToolCallTracer.startDetail("FetchUrl", "{\"url\":\"https://a.com\"}"));
    }

    @Test
    void doneDetail_okWithDiff() {
        String r = ToolCallTracer.doneDetail("WriteFile", "{\"content\":\"x\\ny\"}", true, 1200);
        assertEquals("WriteFile ✓ 1.2s · +2 行", r);
    }

    @Test
    void doneDetail_failureMarksCross() {
        String r = ToolCallTracer.doneDetail("RunShell", "{}", false, 300);
        assertEquals("RunShell ✗ 0.3s", r);
    }

    // ---- 装饰行为 ----

    @Test
    void trace_emitsStartAndDoneAroundCall() {
        ToolCallback delegate = stubDelegate("WriteFile", "ok-result");
        List<String> events = new ArrayList<>();

        ToolCallback traced = ToolCallTracer.trace(List.of(delegate), events::add).get(0);
        String result = traced.call("{\"path\":\"/tmp/a.py\",\"content\":\"x\\ny\"}");

        assertEquals("ok-result", result);
        assertEquals(2, events.size(), "应发起始+结果两条事件: " + events);
        ProgressLine.StageRow start = ProgressLine.decode(events.get(0));
        ProgressLine.StageRow done = ProgressLine.decode(events.get(1));
        assertEquals(ToolCallTracer.STAGE_TOOL, start.stage());
        assertEquals("WriteFile(/tmp/a.py)", start.detail());
        assertEquals(ToolCallTracer.STAGE_TOOL_DONE, done.stage());
        assertTrue(done.detail().startsWith("WriteFile ✓ "), "结果行应以 ✓ 标记: " + done.detail());
        assertTrue(done.detail().contains("+2 行"), "结果行应含 diff 摘要: " + done.detail());
    }

    @Test
    void trace_failureEmitsCrossAndRethrows() {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(def("Boom"));
        when(delegate.call(anyString())).thenThrow(new IllegalStateException("容器执行失败"));
        List<String> events = new ArrayList<>();

        ToolCallback traced = ToolCallTracer.trace(List.of(delegate), events::add).get(0);
        assertThrows(IllegalStateException.class, () -> traced.call("{}"));
        assertEquals(2, events.size(), "失败也应发起始+结果两条事件: " + events);
        assertTrue(ProgressLine.decode(events.get(1)).detail().contains("✗"),
                "失败事件应含 ✗: " + events.get(1));
    }

    @Test
    void trace_nullEmitterReturnsSameList() {
        ToolCallback delegate = stubDelegate("T", "r");
        List<ToolCallback> callbacks = List.of(delegate);
        assertSame(callbacks, ToolCallTracer.trace(callbacks, null), "无 emitter 应零开销直通");
    }

    @Test
    void traceAnnotated_wrapsToolAnnotatedMethods() {
        List<String> events = new ArrayList<>();
        List<ToolCallback> traced = ToolCallTracer.traceAnnotated(List.of(new EchoTools()), events::add);

        assertFalse(traced.isEmpty(), "@Tool 方法应转为回调");
        // MethodToolCallback 对 String 返回值做 JSON 序列化（带引号）
        String result = traced.get(0).call("{\"text\":\"hi\"}");
        assertEquals("\"echo: hi\"", result);
        assertEquals(2, events.size(), "注解工具调用也应发起止事件: " + events);
        assertEquals("EchoTool(hi)", ProgressLine.decode(events.get(0)).detail());
    }

    @Test
    void trace_schemaIsPassedThroughUnchanged() {
        ToolDefinition def = def("WriteFile");
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(def);
        ToolCallback traced = ToolCallTracer.trace(List.of(delegate), e -> {
        }).get(0);
        assertSame(def, traced.getToolDefinition(), "schema 应原样透传（模型不可见追踪）");
    }

    // ---- fixtures ----

    private static ToolCallback stubDelegate(String name, String result) {
        ToolCallback cb = mock(ToolCallback.class);
        when(cb.getToolDefinition()).thenReturn(def(name));
        when(cb.call(anyString())).thenReturn(result);
        return cb;
    }

    private static ToolDefinition def(String name) {
        return ToolDefinition.builder()
                .name(name)
                .description("test tool")
                .inputSchema("{}")
                .build();
    }

    /** @Tool 注解测试桩：注解对象 → ToolCallbacks.from → 装饰链路真实验证 */
    static class EchoTools {

        @Tool(name = "EchoTool", description = "echo the text")
        public String echo(String text) {
            return "echo: " + text;
        }
    }
}
