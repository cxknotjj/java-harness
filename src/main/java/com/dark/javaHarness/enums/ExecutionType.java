package com.dark.javaHarness.enums;

/**
 * Agent 执行类型：决定 chat 用例底层调用 AgentService 的哪种执行方法。
 */
public enum ExecutionType {
    /** 同步执行：executeSync，一次性返回完整结果 */
    SYNC,
    /** 流式执行：executeStream，逐段回调，收集后返回完整结果 */
    STREAM
}
