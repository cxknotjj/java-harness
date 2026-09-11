package com.dark.javaHarness.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.prompt.ToolLazyManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/**
 * ToolLazyLoadDecorator（懒加载装饰器）单测：
 * - decorate 纯委托：以 (ctx.sessionId(), tools) 调用 ToolLazyManager.process 并返回其结果
 * - manager 返回原列表时装饰器原样透传（不额外包装）
 */
class ToolLazyLoadDecoratorTest {

    @Test
    void decorate_delegatesToManager_withSessionId() {
        ToolLazyManager manager = mock(ToolLazyManager.class);
        ToolLazyLoadDecorator decorator = new ToolLazyLoadDecorator(manager);

        List<ToolCallback> tools = List.of(mock(ToolCallback.class));
        List<ToolCallback> wrapped = List.of(mock(ToolCallback.class));
        ToolDecorationContext ctx = new ToolDecorationContext("agent-a", "s1", null, true);

        when(manager.process(eq("s1"), same(tools))).thenReturn(wrapped);

        List<ToolCallback> result = decorator.decorate(ctx, tools);

        assertThat(result).isSameAs(wrapped);
        verify(manager).process(ctx.sessionId(), tools);
    }

    @Test
    void decorate_passesThroughManagerResult() {
        ToolLazyManager manager = mock(ToolLazyManager.class);
        ToolLazyLoadDecorator decorator = new ToolLazyLoadDecorator(manager);

        List<ToolCallback> tools = List.of(mock(ToolCallback.class));
        ToolDecorationContext ctx = new ToolDecorationContext("agent-a", "s2", null, false);

        when(manager.process(eq("s2"), same(tools))).thenReturn(tools);

        List<ToolCallback> result = decorator.decorate(ctx, tools);

        assertThat(result).isSameAs(tools);
    }
}
