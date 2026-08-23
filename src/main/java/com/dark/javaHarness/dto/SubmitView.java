package com.dark.javaHarness.dto;

/**
 * 提交目标响应（POST /api/harness/submit）。
 */
public record SubmitView(String goalId, String status) {
}