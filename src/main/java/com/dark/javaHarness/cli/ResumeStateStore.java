package com.dark.javaHarness.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * /resume 续跑目标的本地持久化存储（从 ChatCli 拆出的状态文件逻辑）：
 * 把 sessionId + goalId 两行写入用户目录的状态文件，跨 CLI 进程保留。
 * 文件格式为 {@code key=value} 行（未知行宽容忽略）；数值为空写空串。
 *
 * <p>恢复时的「会话匹配 + 服务端确认未完成」决策与 UI 提示留在 ChatCli 门面，
 * 本类只负责文件读写与解析（可独立单测）。
 */
public final class ResumeStateStore {

    /** /resume 无参续跑目标的持久化文件（默认用户目录，跨 CLI 进程保留） */
    private static final Path DEFAULT_FILE =
            Path.of(System.getProperty("user.home"), ".javaHarness_resume_state");

    private final Path file;

    /** 默认存储：用户目录 ~/.javaHarness_resume_state */
    public ResumeStateStore() {
        this(DEFAULT_FILE);
    }

    /** 指定文件存储（测试用临时目录注入） */
    ResumeStateStore(Path file) {
        this.file = file;
    }

    /** 持久化的续跑状态：sessionId（会话匹配才生效）+ goalId（/resume 无参续跑目标） */
    record State(String sessionId, String goalId) {
    }

    /** 把 sessionId + goalId 写入状态文件（null 写空串）；IO 失败上抛由调用方决定提示方式 */
    void save(String sessionId, String goalId) throws IOException {
        Files.writeString(file,
                "sessionId=" + (sessionId == null ? "" : sessionId) + "\n"
                        + "goalId=" + (goalId == null ? "" : goalId) + "\n");
    }

    /**
     * 读取持久化状态；文件不存在返回 null。
     * 行解析宽容：未知行忽略，缺键为 null（与存量手工解析语义一致）。
     */
    State load() throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        String stateSessionId = null;
        String stateGoalId = null;
        for (String line : Files.readAllLines(file)) {
            if (line.startsWith("sessionId=")) {
                stateSessionId = line.substring("sessionId=".length()).trim();
            } else if (line.startsWith("goalId=")) {
                stateGoalId = line.substring("goalId=".length()).trim();
            }
        }
        return new State(stateSessionId, stateGoalId);
    }
}
