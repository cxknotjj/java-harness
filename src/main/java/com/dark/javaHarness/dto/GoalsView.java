package com.dark.javaHarness.dto;

import java.util.List;

/**
 * 目标列表响应（GET /api/harness/goals）。
 */
public record GoalsView(List<GoalView> goals) {
}