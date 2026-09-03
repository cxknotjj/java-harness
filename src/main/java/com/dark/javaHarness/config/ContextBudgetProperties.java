package com.dark.javaHarness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 上下文预算配置（app.context.*，全部有内置默认值，yaml 可选覆盖）：
 * 集中管理四层预算——路径 A 会话历史、lead 拆解 prompt、聚合 prompt、
 * 工具结果与工具次数（见 docs/CONTEXT_OPTIMIZATION.md）。
 */
@Component
@ConfigurationProperties(prefix = "app.context")
public class ContextBudgetProperties {

    /** 路径 A 会话历史裁剪预算（token） */
    private int historyBudget = 4000;

    /** lead 拆解 prompt 预算（token），超出对目标文本尾截 */
    private int leadBudget = 4000;

    /** 聚合 prompt 预算（token），超出按子任务等份额截断 */
    private int aggregateBudget = 12000;

    /** 单次 LLM 调用内工具结果可注入上下文的 token 硬预算 */
    private int toolResultBudget = 5000;

    /** 单次 LLM 调用内工具执行次数硬上限 */
    private int toolCallLimit = 12;

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
}
