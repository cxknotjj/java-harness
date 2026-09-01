package com.dark.javaHarness.exception;

/**
 * 续跑冲突：goal 仍在执行中（RUNNING），拒绝再次续跑以防双跑（同一检查点被两个编排实例并发推进）。
 * 对应 HTTP 409 Conflict。
 */
public class ResumeConflictException extends RuntimeException {

    public ResumeConflictException(String message) {
        super(message);
    }
}
