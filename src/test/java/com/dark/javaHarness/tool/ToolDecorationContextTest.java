package com.dark.javaHarness.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * ToolDecorationContext 单测：record 构造后各字段可读，访问器与构造值一一对应。
 */
class ToolDecorationContextTest {

    @Test
    void record_exposesAllFields() {
        Consumer<String> emitter = line -> { };

        ToolDecorationContext ctx = new ToolDecorationContext(
                "coder", "session-1", emitter, true);

        assertThat(ctx.agentName()).isEqualTo("coder");
        assertThat(ctx.sessionId()).isEqualTo("session-1");
        assertThat(ctx.emitter()).isSameAs(emitter);
        assertThat(ctx.callBudgetEnabled()).isTrue();

        ToolDecorationContext noSse = new ToolDecorationContext(
                "reviewer", "session-2", null, false);

        assertThat(noSse.agentName()).isEqualTo("reviewer");
        assertThat(noSse.sessionId()).isEqualTo("session-2");
        assertThat(noSse.emitter()).isNull();
        assertThat(noSse.callBudgetEnabled()).isFalse();
    }
}
