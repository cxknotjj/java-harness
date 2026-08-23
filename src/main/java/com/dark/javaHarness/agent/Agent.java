package com.dark.javaHarness.agent;

import com.dark.javaHarness.domain.Goal;

/**
 * Agent 抽象：负责执行一个 Goal 并产出摘要。
 * 实现类可通过 Spring AI 调用大模型，或直接编程实现。
 */
public interface Agent {

    /** Agent 名称，用于注册与路由。 */
    String name();

    /** 执行目标，返回执行结果摘要（成功后由 AgentService 写入 Goal.summary）。 */
    String execute(Goal goal);
}