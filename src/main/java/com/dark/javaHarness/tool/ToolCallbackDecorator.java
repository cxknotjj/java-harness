package com.dark.javaHarness.tool;

import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.Ordered;

/**
 * 工具装饰器：请求组装期对工具列表施加横切关注点的可插拔组件（观测/预算/懒加载/元工具等）。
 *
 * <p>装饰器与业务工具只通过 {@link ToolCallback} 接口交互，不依赖任何具体工具类；
 * 由 {@code AgentRequestSpecFactory} 按 {@link Ordered#getOrder()} 升序逐个应用
 * （数值小 = 先执行 = 装饰层更贴近真实工具）。
 *
 * <p>顺序契约（默认链 100→200→300→400）：
 * <ol>
 *   <li>观测最内层：只有真实工具执行才产生 SSE 进度行与 tool_call_log 记录</li>
 *   <li>预算包观测外：预算截断的引导文本不产生假观测记录</li>
 *   <li>懒加载最外层：未展开的轻量 schema 不进观测与预算</li>
 *   <li>元工具最后追加：expand_tool/load_skill 不经观测与预算额度</li>
 * </ol>
 *
 * <p>契约：不适用时原样返回传入列表（同一引用，零开销直通）；禁止返回 null
 * （工厂侧 Objects.requireNonNull 快速失败，组装期编程错误不允许静默丢装饰）。
 */
public interface ToolCallbackDecorator extends Ordered {

    /**
     * 装饰工具列表。
     *
     * @param ctx   本次请求的装饰上下文（装饰器只读）
     * @param tools 双通道归一后的工具回调列表（可自由包装，但禁止返回 null）
     * @return 装饰后的列表；不适用时原样返回传入引用
     */
    List<ToolCallback> decorate(ToolDecorationContext ctx, List<ToolCallback> tools);
}
