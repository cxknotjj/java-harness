package com.dark.javaHarness.domain.dto;

/**
 * 提交目标响应（POST /api/harness/submit）。
 */
public record SubmitView(String goalId, String status) {
}