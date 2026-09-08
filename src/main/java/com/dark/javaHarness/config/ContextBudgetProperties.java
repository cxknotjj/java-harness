package com.dark.javaHarness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 上下文预算配置（app.context.*）：集中管理输入侧四层预算（路径 A 会话历史、lead 拆解
 * prompt、聚合 prompt、工具结果与工具次数）与消费侧预算（输出 maxTokens 分档、编排全程
 * 消费上限，见 docs/guides/context-optimization.md）。
 *
 * <p>数值唯一来源是 application.yaml（app.context 块）——本类只做绑定载体，不含任何
 * 预算数字；字段保持 int 缺省 0。全键统一 0 = 不限制：该层预算关闭，组件行为等同
 * 无预算（存量行为）；暂时不需要某项限制时在 yaml 置 0 即可，无需改代码。
 */
@Component
@ConfigurationProperties(prefix = "app.context")
public class ContextBudgetProperties {

    /** 路径 A 会话历史裁剪预算（token）；0 = 不裁剪 */
    private int historyBudget;

    /** lead 拆解 prompt 预算（token），超出对目标文本尾截；0 = 不裁剪 */
    private int leadBudget;

    /** 聚合 prompt 预算（token），超出按子任务等份额截断；0 = 不裁剪 */
    private int aggregateBudget;

    /** 单次 LLM 调用内工具结果可注入上下文的 token 硬预算；0 = 不裁剪 */
    private int toolResultBudget;

    /** 单次 LLM 调用内工具执行次数硬上限；0 = 不限次 */
    private int toolCallLimit;

    /** lead 拆解单次输出上限（maxTokens，生成侧防失控）；0 = 不限制，保持模型默认 */
    private int maxTokensLead;

    /** 聚合与路径 A 直出单次输出上限（maxTokens）——两者同为直出用户的最终回答，共用一档；0 = 不限制 */
    private int maxTokensFinal;

    /** 编排子任务专家单次输出上限（maxTokens）；0 = 不限制 */
    private int maxTokensExpert;

    /**
     * 单次编排全程 token 消费上限：编排内内存账本同步累计各节点调用消耗（真实 usage 优先），
     * 超限后剩余子任务短路跳过、聚合节点照常执行并在 prompt 注入降级说明；0 = 不限制。
     */
    private int orchestrationBudget;

    /**
     * 编排子任务并行扇出限并发：子任务节点在图并行节点内经信号量排队错峰执行（下游 LLM
     * 压力收敛，熔断后的在途超额窗口也随之收窄）；0 = 不限并发（全部并行，存量行为）。
     */
    private int subtaskConcurrency;

    public int getHistoryBudget() {
        return historyBudget;
    }

    public void setHistoryBudget(int historyBudget) {
        this.historyBudget = historyBudget;
    }

    public int getLeadBudget() {
        return leadBudget;
    }

    public void setLeadBudget(int leadBudget) {
        this.leadBudget = leadBudget;
    }

    public int getAggregateBudget() {
        return aggregateBudget;
    }

    public void setAggregateBudget(int aggregateBudget) {
        this.aggregateBudget = aggregateBudget;
    }

    public int getToolResultBudget() {
        return toolResultBudget;
    }

    public void setToolResultBudget(int toolResultBudget) {
        this.toolResultBudget = toolResultBudget;
    }

    public int getToolCallLimit() {
        return toolCallLimit;
    }

    public void setToolCallLimit(int toolCallLimit) {
        this.toolCallLimit = toolCallLimit;
    }

    public int getMaxTokensLead() {
        return maxTokensLead;
    }

    public void setMaxTokensLead(int maxTokensLead) {
        this.maxTokensLead = maxTokensLead;
    }

    public int getMaxTokensFinal() {
        return maxTokensFinal;
    }

    public void setMaxTokensFinal(int maxTokensFinal) {
        this.maxTokensFinal = maxTokensFinal;
    }

    public int getMaxTokensExpert() {
        return maxTokensExpert;
    }

    public void setMaxTokensExpert(int maxTokensExpert) {
        this.maxTokensExpert = maxTokensExpert;
    }

    public int getOrchestrationBudget() {
        return orchestrationBudget;
    }

    public void setOrchestrationBudget(int orchestrationBudget) {
        this.orchestrationBudget = orchestrationBudget;
    }

    public int getSubtaskConcurrency() {
        return subtaskConcurrency;
    }

    public void setSubtaskConcurrency(int subtaskConcurrency) {
        this.subtaskConcurrency = subtaskConcurrency;
    }
}
