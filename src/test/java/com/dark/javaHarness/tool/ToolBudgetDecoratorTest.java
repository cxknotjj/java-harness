package com.dark.javaHarness.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import com.dark.javaHarness.config.ContextBudgetProperties;

/**
 * ToolBudgetDecorator（预算装饰器）单测：
 * - 开关关闭（callBudgetEnabled=false）→ 原列表直通（同一引用，零开销）
 * - 开关开启 → 委托 ToolCallBudget.limit：元素被包装、执行仍真实委托原工具
 * - 空列表 + 开关开启 → 包装后仍为空列表
 * - 顺序契约：ORDER = 200，包观测（100）外——引导文本不产生假观测记录
 */
class ToolBudgetDecoratorTest {

    private ToolBudgetDecorator decoratorWithBudgets(int callLimit, int resultBudget) {
        ContextBudgetProperties budgets = new ContextBudgetProperties();
        budgets.setToolCallLimit(callLimit);
        budgets.setToolResultBudget(resultBudget);
        return new ToolBudgetDecorator(budgets);
    }

    @Test
    void decorate_returnsSameInstance_whenBudgetDisabled() {
        ToolBudgetDecorator decorator = decoratorWithBudgets(1, 5000);
        List<ToolCallback> tools = List.of(mock(ToolCallback.class));
        ToolDecorationContext ctx = new ToolDecorationContext("agent-a", "s1", null, false);

        List<ToolCallback> result = decorator.decorate(ctx, tools);

        assertThat(result).isSameAs(tools);
    }

    @Test
    void decorate_delegatesToLimit_whenEnabled() {
        ToolBudgetDecorator decorator = decoratorWithBudgets(1, 5000);
        ToolCallback real = mock(ToolCallback.class);
        when(real.call("q")).thenReturn("ok");
        List<ToolCallback> tools = List.of(real);
        ToolDecorationContext ctx = new ToolDecorationContext("agent-a", "s1", null, true);

        List<ToolCallback> result = decorator.decorate(ctx, tools);

        // limit 包装发生：新列表、元素为 BudgetedCallback（不再是原实例）
        assertThat(result).isNotSameAs(tools).hasSize(1);
        assertThat(result.get(0)).isNotSameAs(real);
        // 首次执行仍真实委托原工具
        assertThat(result.get(0).call("q")).isEqualTo("ok");
        verify(real).call("q");
    }

    @Test
    void decorate_wrapsEmptyList_whenEnabled() {
        ToolBudgetDecorator decorator = decoratorWithBudgets(1, 5000);
        ToolDecorationContext ctx = new ToolDecorationContext("agent-a", "s1", null, true);

        List<ToolCallback> result = decorator.decorate(ctx, List.of());

        assertThat(result).isEmpty();
    }

    @Test
    void order_is200() {
        assertThat(ToolBudgetDecorator.ORDER).isEqualTo(200);
        assertThat(decoratorWithBudgets(0, 0).getOrder()).isEqualTo(200);
    }
}
