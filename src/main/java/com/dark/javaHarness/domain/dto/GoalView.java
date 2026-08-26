package com.dark.javaHarness.domain.dto;

import com.dark.javaHarness.domain.Goal;

/**
 * 目标视图对象（/api/harness 返回用），隔离 domain 模型与表现层。
 */
public record GoalView(
        String id,
        String objective,
        String status,
        String summary) {

    /** 由领域模型 Goal 转换 */
    public static GoalView from(Goal goal) {
        return new GoalView(goal.id(), goal.objective(), goal.status().name(), goal.summary());
    }
}