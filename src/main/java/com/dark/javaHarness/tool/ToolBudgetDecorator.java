package com.dark.javaHarness.tool;

import java.util.List;
import org.springframework.ai.tool.ToolCallback;

import com.dark.javaHarness.config.ContextBudgetProperties;

/**
 * 预算装饰器：编排路径开关开启时给工具列表套上次数/token 双重硬上限（见
 * {@link ToolCallBudget#limit(List, int, int)}），非编排路径原列表直通（同一引用）。
 *
 * <p>顺序契约中位于 Order 200——包在观测（Order 100）外层：次数耗尽/预算耗尽时
 * 返回的引导文本不经过观测层，不产生假观测记录；观测层的执行计数只统计真实发生
 * 的工具执行。包装本身在请求组装期完成，零工具执行时零开销。
 *
 * <p>与原组装实现（AgentRequestSpecFactory 硬编码段）的行为差异：原条件为
 * 「emitter 非空 && 预算开关」，本装饰器仅判预算开关——开关独立于 SSE emitter 生效。
 * 当前两条调用路径下等价（编排路径 emitter 恒非空、GA 路径开关恒关），但若未来
 * 无 SSE 链路（如 QQ 渠道）开启预算开关，本装饰器会挂预算而旧实现不会。
 *
 * <p>装配不走 Spring 容器：由 DefaultToolDecorators.defaults 静态工厂构造，
 * 构造注入 {@link ContextBudgetProperties} 读取 app.context 预算数值。
 */
public class ToolBudgetDecorator implements ToolCallbackDecorator {

    /** 固定顺序 200：包观测（100）外、懒加载（300）内 */
    public static final int ORDER = 200;

    private final ContextBudgetProperties budgets;

    public ToolBudgetDecorator(ContextBudgetProperties budgets) {
        this.budgets = budgets;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public List<ToolCallback> decorate(ToolDecorationContext ctx, List<ToolCallback> tools) {
        if (!ctx.callBudgetEnabled()) {
            return tools;
        }
        return ToolCallBudget.limit(tools, budgets.getToolCallLimit(), budgets.getToolResultBudget());
    }
}
