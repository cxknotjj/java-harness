package com.dark.javaHarness.tool;

import com.dark.javaHarness.prompt.ToolLazyManager;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/**
 * 懒加载装饰器：装饰链中最外层（Order 300），把请求级工具面交给
 * {@link ToolLazyManager#process} 做两段式轻量化——未展开工具压成轻量 schema
 * 并追加 expand_tool 元工具。
 *
 * <p>置于最外层的目的：未展开的轻量 schema 不进入观测（ToolCallTracer）与
 * 预算（ToolCallBudget）——引导文本不是真实工具执行，不产生 SSE 工具行、
 * 不记录 tool_call_log、不消耗调用额度；已展开工具透传的是装饰后 callback，
 * 观测与预算照常生效。元工具 expand_tool 亦由 manager 追加，同样不经观测与预算。
 *
 * <p>本装饰器不做任何自判条件、全量委托：开关（app.prompt.lazy-tools.enabled）
 * 关闭、sessionId 缺失或原始面为空时，由 manager 内部原样全量透传，
 * 回退全量注入现状（装饰器保持零逻辑直通，避免两处判重漂移）。
 *
 * <p>装配：普通类而非 {@code @Component}，经 {@code DefaultToolDecorators.defaults}
 * 静态工厂统一装配进默认装饰链。
 */
public class ToolLazyLoadDecorator implements ToolCallbackDecorator {

    /** 装饰链顺序：300 = 最外层（观测 100 → 预算 200 → 懒加载 300） */
    public static final int ORDER = 300;

    private final ToolLazyManager lazyTools;

    public ToolLazyLoadDecorator(ToolLazyManager lazyTools) {
        this.lazyTools = lazyTools;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public List<ToolCallback> decorate(ToolDecorationContext ctx, List<ToolCallback> tools) {
        return lazyTools.process(ctx.sessionId(), tools);
    }
}
