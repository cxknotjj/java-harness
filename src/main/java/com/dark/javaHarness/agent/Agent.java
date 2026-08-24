package com.dark.javaHarness.agent;

import com.dark.javaHarness.domain.Goal;
import java.util.function.Consumer;

/**
 * Agent 抽象：负责执行一个 Goal 并产出摘要。
 * 实现类可通过 Spring AI 调用大模型，或直接编程实现。
 */
public interface Agent {

    /** Agent 名称，用于注册与路由。 */
    String name();

    /** 执行目标，返回执行结果摘要（成功后由 AgentService 写入 Goal.summary）。 */
    String execute(Goal goal);

    /**
     * 流式执行目标：逐片段（token）回调 onToken，不返回结果。
     * 完整结果需要由调用方（如 AgentService）在 onToken 回调中自行拼接。
     * 默认实现退化为同步 execute 后一次性回调；需要真正流式的实现应覆写。
     */
    default void executeStream(Goal goal, Consumer<String> onToken) {
        String result = execute(goal);
        if (onToken != null) {
            onToken.accept(result);
        }
    }
}