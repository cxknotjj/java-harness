package com.dark.javaHarness.domain.dto;

/**
 * Goal 状态查询视图（GET /api/chat/goal-status 响应体）。
 * CLI 启动恢复续跑记录时用于向服务端确认任务是否已完成。
 */
public record GoalStatusView(String goalId, String status) {
}
