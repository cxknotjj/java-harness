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

    /**
     * 将全部 RUNNING 状态的目标批量标记为 FAILED（服务启动时清理僵尸目标）。
     *
     * <p>服务非正常退出（强杀进程）时 Reactor 的 cancel/error 回调不会执行，
     * goal 会永久残留 RUNNING，导致 /resume 被 409 拦截。单实例部署下
     * 启动时必然没有正在执行的编排，可安全统一清理。
     *
     * @return 清理的目标数量
     */
    int failAllRunning(String reason);
}