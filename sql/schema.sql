-- ============================================================
-- javaHarness - Schema 初始化脚本
-- 说明：匹配 com.dark.javaHarness.core.goal.Goal 实体。
--       若目标 agent_name 字段，请同步更新实体。
-- ============================================================

CREATE TABLE IF NOT EXISTS goal (
    id           VARCHAR(32)  NOT NULL COMMENT '目标ID（UUID）',
    objective    TEXT         NOT NULL COMMENT '目标描述',
    agent_name   VARCHAR(64)  NOT NULL COMMENT '负责执行的 Agent 名',
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED',
    summary      TEXT         NULL COMMENT '执行结论/结果摘要',
    created_at   DATETIME     NOT NULL COMMENT '创建时间',
    finished_at  DATETIME     NULL COMMENT '结束时间',
    PRIMARY KEY (id),
    KEY idx_agent_name (agent_name),
    KEY idx_status    (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 目标表';

-- ============================================================
-- 会话记忆表：按 sessionId 保存多轮对话消息（role + content）
-- 供 GeneralAssistantAgent 实现多轮会话记忆。
-- ============================================================
CREATE TABLE IF NOT EXISTS chat_memory (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id    VARCHAR(64)  NOT NULL COMMENT '会话ID（按此分组记忆）',
    role          VARCHAR(16)  NOT NULL COMMENT 'user/assistant/system',
    content       TEXT         NOT NULL COMMENT '消息内容',
    created_at    DATETIME     NOT NULL COMMENT '消息时间',
    KEY idx_session_id (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话记忆表';