package com.dark.javaHarness.domain;

import java.time.LocalDateTime;

/**
 * 一个待完成的目标（Goal）。
 * 由 GoalService 维护其生命周期状态。
 */
public class Goal {

    public enum Status {
        PENDING, RUNNING, SUCCEEDED, FAILED
    }

    private final String id;
    private final String objective;
    /** 会话ID：用于多轮会话记忆分组；为空表示无会话记忆 */
    private final String sessionId;
    private Status status;
    private final LocalDateTime createdAt;
    private LocalDateTime finishedAt;
    private String summary;

    public Goal(String id, String objective) {
        this(id, objective, null);
    }

    public Goal(String id, String objective, String sessionId) {
        this.id = id;
        this.objective = objective;
        this.sessionId = sessionId;
        this.status = Status.PENDING;
        this.createdAt = LocalDateTime.now();
    }

    public void markRunning() {
        this.status = Status.RUNNING;
    }

    public void succeed(String summary) {
        this.status = Status.SUCCEEDED;
        this.summary = summary;
        this.finishedAt = LocalDateTime.now();
    }

    public void fail(String summary) {
        this.status = Status.FAILED;
        this.summary = summary;
        this.finishedAt = LocalDateTime.now();
    }

    public String id() {
        return id;
    }

    public String objective() {
        return objective;
    }

    public String sessionId() {
        return sessionId;
    }

    public Status status() {
        return status;
    }

    public LocalDateTime createdAt() {
        return createdAt;
    }

    public LocalDateTime finishedAt() {
        return finishedAt;
    }

    public String summary() {
        return summary;
    }
}