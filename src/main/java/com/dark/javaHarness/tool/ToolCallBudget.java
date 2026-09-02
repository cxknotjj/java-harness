package com.dark.javaHarness.tool;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.tool.ToolCallback;

/**
 * 工具调用预算护栏：给一批 ToolCallback 套上两层硬上限——
 * 「单次 LLM 调用内最多执行 N 次」+「工具结果注入上下文的总量 ≤ M token」。
 *
 * <p>背景：Spring AI 工具循环对轮数与上下文体积均无硬上限，模型可能连续几十次抓网页——
 * 每轮全量重发之前累积的网页全文，token 消耗按轮数平方级膨胀（实测单子任务累计 prompt
 * 达 262 万）。提示词约束（≤8 次）是软的，本护栏在服务端硬封顶：
 * <ul>
 *   <li>次数超限 → 不再执行工具，返回引导文本让模型基于已有信息作答</li>
 *   <li>结果超预算 → 截断到剩余预算（带标记），预算耗尽后不再执行工具</li>
 * </ul>
 *
 * <p>计数器在一次 LLM 调用（一个 buildSpec）内共享，跨调用不累计。
 */
public final class ToolCallBudget {

    /** 次数超限引导文本（含要求，帮助模型立即收束） */
    public static final String EXHAUSTED_MESSAGE =
            "工具调用次数已达本任务硬上限，不能再执行任何工具。"
                    + "请立即停止调用工具，直接基于已获取的信息输出最终结果。";

    /** 上下文预算耗尽引导文本 */
    public static final String CONTEXT_EXHAUSTED_MESSAGE =
            "工具结果上下文预算已用尽，不能再注入新内容。"
                    + "请立即停止调用工具，直接基于已获取的信息输出最终结果。";

    /** 截断标记（追加在被裁剪的工具结果末尾，提示模型内容不完整） */
    public static final String TRUNCATED_SUFFIX = "…[内容已按上下文预算截断]";

    private static final int SUFFIX_TOKENS = TokenEstimator.estimateTokens(TRUNCATED_SUFFIX);

    private ToolCallBudget() {
    }

    /**
     * 包装回调列表：只限次数，不限 token（无 token 预算场景）。
     */
    public static List<ToolCallback> limit(List<ToolCallback> callbacks, int maxCalls) {
        return limit(callbacks, maxCalls, Integer.MAX_VALUE);
    }

    /**
     * 包装回调列表：共享计数器与 token 预算——
     * 前 {@code maxCalls} 次正常执行（结果裁剪到剩余 token 预算内），
     * 次数或 token 任一超限后一律返回引导文本（不触发真实工具）。
     */
    public static List<ToolCallback> limit(List<ToolCallback> callbacks, int maxCalls, int maxTokens) {
        AtomicInteger counter = new AtomicInteger();
        AtomicInteger usedTokens = new AtomicInteger();
        return callbacks.stream()
                .map(cb -> (ToolCallback) new BudgetedCallback(cb, counter, maxCalls, usedTokens, maxTokens))
                .toList();
    }

    private static final class BudgetedCallback implements ToolCallback {

        private final ToolCallback delegate;
        private final AtomicInteger counter;
        private final int maxCalls;
        private final AtomicInteger usedTokens;
        private final int maxTokens;

        BudgetedCallback(ToolCallback delegate, AtomicInteger counter, int maxCalls,
                         AtomicInteger usedTokens, int maxTokens) {
            this.delegate = delegate;
            this.counter = counter;
            this.maxCalls = maxCalls;
            this.usedTokens = usedTokens;
            this.maxTokens = maxTokens;
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public String call(String toolInput) {
            return budgetedCall(toolInput);
        }

        @Override
        public String call(String toolInput, org.springframework.ai.chat.model.ToolContext toolContext) {
            return budgetedCall(toolInput, toolContext);
        }

        private String budgetedCall(Object... args) {
            if (counter.incrementAndGet() > maxCalls) {
                return EXHAUSTED_MESSAGE;
            }
            if (usedTokens.get() >= maxTokens) {
                return CONTEXT_EXHAUSTED_MESSAGE;
            }
            String out = args.length == 1
                    ? delegate.call((String) args[0])
                    : delegate.call((String) args[0], (org.springframework.ai.chat.model.ToolContext) args[1]);
            return capToBudget(out);
        }

        /** 结果超出剩余预算时截断（带标记），并把实际占用计入预算 */
        private String capToBudget(String out) {
            if (out == null || out.isEmpty()) {
                return out;
            }
            int est = TokenEstimator.estimateTokens(out);
            int remaining = maxTokens - usedTokens.get();
            if (est <= remaining) {
                usedTokens.addAndGet(est);
                return out;
            }
            // 截断目标 = 剩余预算 - 截断标记自身占用
            int target = Math.max(0, remaining - SUFFIX_TOKENS);
            String trimmed = target == 0 ? "" : truncateByTokens(out, target);
            usedTokens.addAndGet(TokenEstimator.estimateTokens(trimmed) + SUFFIX_TOKENS);
            return trimmed + TRUNCATED_SUFFIX;
        }

        /** 二分查找最大前缀长度，使估算 token 数 ≤ limit（估算对长度单调） */
        private static String truncateByTokens(String text, int limit) {
            int lo = 0;
            int hi = text.length();
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (TokenEstimator.estimateTokens(text.substring(0, mid)) <= limit) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return text.substring(0, lo);
        }
    }
}
