package com.dark.javaHarness.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.dark.javaHarness.config.ContextBudgetProperties;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 编排消费上限熔断（预算账本）：多 Agent 编排各节点（lead/子任务/聚合）共享的
 * token 记账与熔断组件，从编排器中独立出的纯关注点。
 *
 * <p>编排预算开关开启（orchestration-budget > 0）时向编排 input 注入共享账本：
 * AtomicLong 累计消耗 + AtomicBoolean 估算标记，节点经 state 读取（Replace 策略注册，
 * 续跑时新账本覆盖旧值）。0 = 不限制：不注入不记账不熔断，存量行为不变。
 */
final class OrchestrationBudget {

    /**
     * 编排预算账本（input 注入，节点间共享同一 AtomicLong 实例）：
     * 各节点调用的每轮 LLM roundtrip（含单次 call 内部工具循环）经 caller 门控句柄
     * 增量记账并熔断复检（真实 usage 优先，无则按输出估算）。orchestration-budget=0
     * 时不注入（不记账不熔断，存量行为）。
     */
    static final String K_TOKEN_LEDGER = "tokenLedger";
    /** 账本估算标记：任一调用无真实 usage 回包（按输出估算）时置位，聚合降级说明注明口径 */
    static final String K_TOKEN_ESTIMATED = "tokenEstimated";

    private final ContextBudgetProperties budgets;

    OrchestrationBudget(ContextBudgetProperties budgets) {
        this.budgets = budgets;
    }

    /** 预算开关开启时向编排 input 注入共享账本（0 = 不注入，存量行为） */
    void putLedger(Map<String, Object> input) {
        if (budgets.getOrchestrationBudget() > 0) {
            input.put(K_TOKEN_LEDGER, new AtomicLong());
            input.put(K_TOKEN_ESTIMATED, new AtomicBoolean(false));
        }
    }

    /** 当前账本累计消耗（无账本返回 0，仅日志展示用） */
    static long ledgerValue(OverAllState state) {
        return state.value(K_TOKEN_LEDGER, AtomicLong.class)
                .map(AtomicLong::get).orElse(0L);
    }

    /**
     * 预算账本句柄工厂（无账本返回 null = 不记账不熔断）：
     * <ul>
     *   <li>{@code enforceBudget=true}（lead/子任务节点）：门控句柄——caller 在发起调用前与
     *       每轮 LLM roundtrip 的 usage 帧上做熔断判定。并行扇出下各调用共享同一 AtomicLong，
     *       任一调用的消耗入账后其余调用的下一轮 roundtrip 即可见（原「批前检查只见 lead 消耗」
     *       的结构性盲区由此消除）；配合 subtask-concurrency 限并发错峰，超额窗口进一步收窄；
     *   <li>{@code enforceBudget=false}（聚合节点）：record-only 句柄——聚合必发不受熔断，
     *       overBudget 恒 false，仅记账。
     * </ul>
     * AtomicLong/AtomicBoolean 保证并行写安全（熔断为近似判定，竞态窗口见限并发说明）。
     */
    AgentChatCaller.BudgetLedger ledgerHandle(OverAllState state, boolean enforceBudget) {
        AtomicLong ledger =
                state.value(K_TOKEN_LEDGER, AtomicLong.class).orElse(null);
        if (ledger == null) {
            return null;
        }
        AtomicBoolean estimated = state.value(K_TOKEN_ESTIMATED, AtomicBoolean.class).orElse(null);
        int budget = budgets.getOrchestrationBudget();
        return new AgentChatCaller.BudgetLedger() {
            @Override
            public boolean overBudget() {
                return enforceBudget && budget > 0 && ledger.get() >= budget;
            }

            @Override
            public void recordUsage(int totalTokens, boolean est) {
                ledger.addAndGet(totalTokens);
                if (est && estimated != null) {
                    estimated.set(true);
                }
            }
        };
    }

    /**
     * 预算降级说明（聚合 prompt 前置，聚合节点必发不受熔断）：
     * N 个子任务因预算超限未执行 + 已消耗/上限数字 + 消耗口径（估算标记置位时注明含估算值，
     * 与 llm_call_log.tokens_estimated 语义一致；全为真实 usage 则注明）。
     */
    String degradationNote(int skipped, OverAllState state) {
        AtomicBoolean estimated = state.value(K_TOKEN_ESTIMATED, AtomicBoolean.class).orElse(null);
        String caliber = estimated != null && estimated.get()
                ? "消耗含估算值（部分调用无真实 usage 回包，按输出文本估算）"
                : "消耗为真实 usage 统计";
        return "【预算降级说明】本次编排 token 消费已达上限（已消耗 " + ledgerValue(state)
                + " / 上限 " + budgets.getOrchestrationBudget() + " token，" + caliber + "），"
                + skipped + " 个子任务因预算超限未执行，以下子任务结果不完整。"
                + "请基于已有内容汇总最终回答，并在回答开头简要说明部分内容因预算限制未覆盖。";
    }
}
