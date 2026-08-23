package com.dark.javaHarness.service;

import com.dark.javaHarness.domain.Goal;
import java.util.List;
import java.util.Optional;

/**
 * 目标服务：负责创建、查询和更新 Goal 的生命周期状态。
 */
public interface GoalService {

    /** 创建目标（无会话记忆） */
    Goal create(String objective);

    /** 创建目标（带会话记忆） */
    Goal create(String objective, String sessionId);

    /** 按 id 查询目标 */
    Optional<Goal> get(String id);

    /** 查询全部目标 */
    List<Goal> all();

    /** 将 Goal 当前状态持久化（状态变更后调用） */
    void update(Goal goal);
}