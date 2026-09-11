package com.dark.javaHarness.tool;

import com.dark.javaHarness.service.impl.LlmCallRecorder;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/**
 * 观测装饰器：装饰链最内层（{@link #ORDER} = 100），把工具回调包装为
 * {@link ToolCallTracer} 追踪版本——只有真实工具执行才产生 SSE 进度行
 * （tool / tool-done）与 tool_call_log 观测记录，预算截断、懒加载引导等
 * 非真实执行路径不会产生假观测。
 *
 * <p>触发条件：{@code ctx.emitter()} 与 {@code recorder} 任一非空即装饰；
 * 两者皆空时同一引用零开销直通（编译期无观测需求的调用方不付任何运行时代价）。
 * recorder 通道独立于 emitter 存在，QQ 渠道 / API 直调等无 SSE 链路
 * （emitter == null）由此补齐观测盲区，tool_call_log 照常落库。
 *
 * <p>普通类而非 {@code @Component}：当前由 {@code DefaultToolDecorators.defaults}
 * 静态工厂装配，未来才 bean 化；{@code recorder} 可为 null（单测 / 纯 SSE 场景）。
 */
public class ToolObservationDecorator implements ToolCallbackDecorator {

    /** 装饰链顺序：数值最小 = 最先应用 = 最贴近真实工具（观测必须最内层） */
    public static final int ORDER = 100;

    private final LlmCallRecorder recorder;

    public ToolObservationDecorator(LlmCallRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public List<ToolCallback> decorate(ToolDecorationContext ctx, List<ToolCallback> tools) {
        if (ctx.emitter() == null && recorder == null) {
            return tools;
        }
        return ToolCallTracer.trace(tools, ctx.emitter(), ctx.agentName(), ctx.sessionId(),
                recorder == null ? null : recorder::recordToolCall);
    }
}
