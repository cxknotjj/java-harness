package com.dark.javaHarness.domain;

import com.dark.javaHarness.enums.GoalStatus;
import java.time.LocalDateTime;

/**
 * 一个待完成的目标（Goal）。
 * 由 GoalService 维护其生命周期状态。
 */
public class Goal {

    private final String id;
    private final String objective;
    /** 会话ID：用于多轮会话记忆分组；为空表示无会话记忆 */
    private final String sessionId;
    private GoalStatus status;
    private final LocalDateTime createdAt;
    private LocalDateTime finishedAt;
    private String summary;

    /** 构造目标（无会话记忆） */
    public Goal(String id, String objective) {
        this(id, objective, null);
    }

    /** 构造目标（带会话记忆），初始状态 PENDING */
    public Goal(String id, String objective, String sessionId) {
        this(id, objective, sessionId, GoalStatus.PENDING, LocalDateTime.now(), null, null);
    }

    /** 从持久化数据恢复完整状态 */
    public Goal(String id, String objective, String sessionId,
                GoalStatus status, LocalDateTime createdAt, LocalDateTime finishedAt, String summary) {
        this.id = id;
        this.objective = objective;
        this.sessionId = sessionId;
        this.status = status;
        this.createdAt = createdAt;
        this.finishedAt = finishedAt;
        this.summary = summary;
    }

    /** 标记目标进入执行中状态 */
    public void markRunning() {
        this.status = GoalStatus.RUNNING;
    }

    /** 标记目标成功并留存摘要 */
    public void succeed(String summary) {
        this.status = GoalStatus.SUCCEEDED;
        this.summary = summary;
        this.finishedAt = LocalDateTime.now();
    }

    /** 标记目标失败并留存原因 */
    public void fail(String summary) {
        this.status = GoalStatus.FAILED;
        this.summary = summary;
        this.finishedAt = LocalDateTime.now();
    }

    /** 目标ID */
    public String id() {
        return id;
    }

    /** 目标描述（要完成的事） */
    public String objective() {
        return objective;
    }

    /** 关联的会话ID */
    public String sessionId() {
        return sessionId;
    }

    /** 当前生命周期状态 */
    public GoalStatus status() {
        return status;
    }

    /** 创建时间 */
    public LocalDateTime createdAt() {
        return createdAt;
    }

    /** 完成时间（成功或失败时设置） */
    public LocalDateTime finishedAt() {
        return finishedAt;
    }

    /** 执行摘要（成功时结果 / 失败时原因） */
    public String summary() {
        return summary;
    }
}