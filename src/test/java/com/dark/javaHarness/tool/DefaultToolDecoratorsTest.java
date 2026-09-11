package com.dark.javaHarness.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dark.javaHarness.config.ContextBudgetProperties;
import com.dark.javaHarness.prompt.SkillManager;
import com.dark.javaHarness.prompt.ToolLazyManager;
import com.dark.javaHarness.service.impl.LlmCallRecorder;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DefaultToolDecorators#defaults} 单元测试。
 *
 * <p>核心目标：顺序断言防漂移——默认装饰链必须保持文档契约中的
 * 观测(100) → 预算(200) → 懒加载(300) → 元工具(400) 顺序。
 */
class DefaultToolDecoratorsTest {

    private final ToolLazyManager lazyTools = null;
    private final SkillManager skillManager = null;
    private final ContextBudgetProperties budgets = new ContextBudgetProperties();
    private final LlmCallRecorder recorder = null;

    @Test
    @DisplayName("默认装饰链按文档顺序装配：Observation→Budget→LazyLoad→SkillMeta")
    void defaults_returnsChainInDocumentedOrder() {
        List<ToolCallbackDecorator> chain =
                DefaultToolDecorators.defaults(lazyTools, skillManager, budgets, recorder);

        assertThat(chain).hasSize(4);

        // 类型顺序断言（containsExactly 顺序敏感）
        assertThat(chain)
                .<Class<? extends ToolCallbackDecorator>>extracting(ToolCallbackDecorator::getClass)
                .containsExactly(
                        ToolObservationDecorator.class,
                        ToolBudgetDecorator.class,
                        ToolLazyLoadDecorator.class,
                        SkillMetaToolDecorator.class);

        // Order 严格升序断言（数值小 = 先执行 = 贴近真实工具）
        assertThat(chain)
                .extracting(ToolCallbackDecorator::getOrder)
                .containsExactly(100, 200, 300, 400)
                .isSorted();
    }

    @Test
    @DisplayName("默认装饰链为不可变列表，禁止外部修改")
    void defaults_returnsImmutableList() {
        List<ToolCallbackDecorator> chain =
                DefaultToolDecorators.defaults(lazyTools, skillManager, budgets, recorder);

        assertThat(chain).isInstanceOf(List.of().getClass());
        assertThatThrownBy(() -> chain.add(new ToolBudgetDecorator(budgets)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
