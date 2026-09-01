package com.dark.javaHarness.tool;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.tool.ToolCallback;

/**
 * 工具调用预算护栏：给一批 ToolCallback 套上「单次 LLM 调用内最多执行 N 次」的硬上限。
 *
 * <p>背景：Spring AI 工具循环对轮数无硬上限，模型可能连续几十次抓网页——每轮全量重发
 * 之前累积的网页全文，token 消耗按轮数平方级膨胀（实测单子任务累计 prompt 达 262 万）。
 * 提示词约束（≤8 次）是软的，本护栏在服务端硬封顶：超限后不再执行工具，直接返回引导
 * 文本让模型基于已有信息作答，循环自然终止。
 *
 * <p>计数器在一次 LLM 调用（一个 buildSpec）内共享，跨调用不累计。
 */
public final class ToolCallBudget {

    /** 工具返回的超限引导文本（含要求，帮助模型立即收束） */
    public static final String EXHAUSTED_MESSAGE =
            "工具调用次数已达本任务硬上限，不能再执行任何工具。"
                    + "请立即停止调用工具，直接基于已获取的信息输出最终结果。";

    private ToolCallBudget() {
    }

    /**
     * 包装回调列表：共享计数器，前 {@code maxCalls} 次正常执行，之后一律返回
     * {@link #EXHAUSTED_MESSAGE}（不触发真实工具）。
     */
    public static List<ToolCallback> limit(List<ToolCallback> callbacks, int maxCalls) {
        AtomicInteger counter = new AtomicInteger();
        return callbacks.stream()
                .map(cb -> (ToolCallback) new BudgetedCallback(cb, counter, maxCalls))
                .toList();
    }

    private static final class BudgetedCallback implements ToolCallback {

        private final ToolCallback delegate;
        private final AtomicInteger counter;
        private final int maxCalls;

        BudgetedCallback(ToolCallback delegate, AtomicInteger counter, int maxCalls) {
            this.delegate = delegate;
            this.counter = counter;
            this.maxCalls = maxCalls;
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public String call(String toolInput) {
            if (counter.incrementAndGet() > maxCalls) {
                return EXHAUSTED_MESSAGE;
            }
            return delegate.call(toolInput);
        }

        @Override
        public String call(String toolInput, org.springframework.ai.chat.model.ToolContext toolContext) {
            if (counter.incrementAndGet() > maxCalls) {
                return EXHAUSTED_MESSAGE;
            }
            return delegate.call(toolInput, toolContext);
        }
    }
}
