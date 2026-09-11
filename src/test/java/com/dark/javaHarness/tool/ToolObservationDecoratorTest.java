package com.dark.javaHarness.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.agent.ProgressLine;
import com.dark.javaHarness.service.impl.LlmCallRecorder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * ToolObservationDecorator 单测：
 * - emitter 与 recorder 皆空 → 同一引用零开销直通
 * - 仅 recorder 非空（emitter=null）→ 仍装饰，真实执行后投递 tool_call_log（QQ 渠道盲区补齐）
 * - 仅 emitter 非空（recorder=null）→ 仍装饰，真实执行前后发 SSE 起止行
 */
class ToolObservationDecoratorTest {

    @Test
    void decorate_returnsSameInstance_whenEmitterAndRecorderNull() {
        List<ToolCallback> tools = List.of(stubDelegate("WriteFile", "ok-result"));
        ToolObservationDecorator decorator = new ToolObservationDecorator(null);

        List<ToolCallback> result = decorator.decorate(ctx("general", "s1", null), tools);

        assertThat(result).isSameAs(tools);
    }

    @Test
    void decorate_wrapsTools_whenRecorderOnly() {
        ToolCallback delegate = stubDelegate("WriteFile", "ok-result");
        List<ToolCallback> tools = List.of(delegate);
        LlmCallRecorder recorder = mock(LlmCallRecorder.class);
        ToolObservationDecorator decorator = new ToolObservationDecorator(recorder);

        List<ToolCallback> result = decorator.decorate(ctx("general", "s1", null), tools);

        assertThat(result).hasSameSizeAs(tools);
        assertThat(result.get(0)).isNotSameAs(delegate);

        String out = result.get(0).call("{\"path\":\"/tmp/a.py\"}");
        assertThat(out).isEqualTo("ok-result");
        verify(recorder).recordToolCall(any());
    }

    @Test
    void decorate_wrapsTools_whenEmitterOnly() {
        ToolCallback delegate = stubDelegate("WriteFile", "ok-result");
        List<ToolCallback> tools = List.of(delegate);
        List<String> lines = new ArrayList<>();
        ToolObservationDecorator decorator = new ToolObservationDecorator(null);

        List<ToolCallback> result = decorator.decorate(ctx("general", "s1", lines::add), tools);

        assertThat(result).hasSameSizeAs(tools);
        assertThat(result.get(0)).isNotSameAs(delegate);

        result.get(0).call("{\"path\":\"/tmp/a.py\"}");
        assertThat(lines).hasSize(2);
        assertThat(ProgressLine.decode(lines.get(0)).stage()).isEqualTo(ToolCallTracer.STAGE_TOOL);
        assertThat(ProgressLine.decode(lines.get(1)).stage())
                .isEqualTo(ToolCallTracer.STAGE_TOOL_DONE);
    }

    // ---- fixtures ----

    private static ToolDecorationContext ctx(String agentName, String sessionId,
            Consumer<String> emitter) {
        return new ToolDecorationContext(agentName, sessionId, emitter, false);
    }

    private static ToolCallback stubDelegate(String name, String result) {
        ToolCallback cb = mock(ToolCallback.class);
        when(cb.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name(name)
                .description("test tool")
                .inputSchema("{}")
                .build());
        when(cb.call(anyString())).thenReturn(result);
        return cb;
    }
}
