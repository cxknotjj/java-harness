package com.dark.javaHarness.tool;

import com.dark.javaHarness.config.ContextBudgetProperties;
import com.dark.javaHarness.prompt.SkillManager;
import com.dark.javaHarness.prompt.ToolLazyManager;
import com.dark.javaHarness.service.impl.LlmCallRecorder;
import java.util.List;

/**
 * 默认装饰链工厂：工具装饰链的单一事实来源（默认装配点）。
 *
 * <p>新增装饰器 = 新增一个 {@link ToolCallbackDecorator} 实现并在 {@link #defaults}
 * 登记一行；未来 AgentRequestSpecFactory bean 化后可改为 Spring List 自动收集。
 *
 * <p>顺序契约（Order 升序，数值小 = 先执行 = 装饰层更贴近真实工具）：
 * 观测(100) → 预算(200) → 懒加载(300) → 元工具(400)，
 * 由 {@code DefaultToolDecoratorsTest} 顺序断言防漂移。
 */
public final class DefaultToolDecorators {

    private DefaultToolDecorators() {
    }

    /** 默认装饰链：按 Order 升序返回不可变列表 */
    public static List<ToolCallbackDecorator> defaults(ToolLazyManager lazyTools,
                                                       SkillManager skillManager,
                                                       ContextBudgetProperties budgets,
                                                       LlmCallRecorder recorder) {
        return List.of(
                new ToolObservationDecorator(recorder),
                new ToolBudgetDecorator(budgets),
                new ToolLazyLoadDecorator(lazyTools),
                new SkillMetaToolDecorator(skillManager));
    }
}
