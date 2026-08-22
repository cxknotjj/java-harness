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
-- 会话消息表：与 session 一对一，每个会话仅一行，
-- content 以 JSON 形式存储该会话的【完整会话上下文】：
-- [{"role":"user","content":"..."},{"role":"assistant","content":"..."},...]
-- 每轮对话后整体覆盖更新该行。
-- （该表已由人工创建，此处保留幂等定义与实际结构一致；
--   已有环境需手工执行一对一改造，见文末 ALTER 语句）
-- ============================================================
CREATE TABLE IF NOT EXISTS session_messages (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '消息唯一主键ID',
    session_id  VARCHAR(64)  NOT NULL COMMENT '关联的会话ID，与 session 一对一，一个会话仅一行',
    tenant_id   VARCHAR(64)  NOT NULL COMMENT '租户隔离ID，用于SaaS多租户场景下的数据物理/逻辑隔离',
    role        VARCHAR(20)  NOT NULL COMMENT '消息角色标识（system/user/assistant/tool）',
    content     TEXT         NOT NULL COMMENT '消息正文内容（完整会话上下文的 JSON 快照）',
    token_count INT          NULL DEFAULT 0 COMMENT '消耗的 Token 数量',
    metadata    JSON         NULL COMMENT '扩展元数据：工具调用参数、API耗时、引用文档ID等动态扩展信息',
    created_at  TIMESTAMP    NULL DEFAULT CURRENT_TIMESTAMP COMMENT '消息创建时间戳，用于按时间顺序还原对话历史',
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_id (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Agent 会话消息表';

-- 已有环境的一对一改造（先去重保留每会话最新一行，再加唯一约束）：
-- DELETE m FROM session_messages m
-- JOIN (SELECT session_id, MAX(id) AS keep_id FROM session_messages GROUP BY session_id) k
--   ON m.session_id = k.session_id AND m.id <> k.keep_id;
-- ALTER TABLE session_messages
--   DROP INDEX idx_session_id,
--   ADD UNIQUE KEY uk_session_id (session_id);

-- ============================================================
-- 会话表：一个会话（session）对应一次与 Agent 的连续对话，
-- 关联创建者与所属 Agent，软删除用 is_delete 标记。
-- ============================================================
CREATE TABLE IF NOT EXISTS `session` (
    session_id     BIGINT(8)    NOT NULL AUTO_INCREMENT COMMENT '会话ID' ,
    agent_id       INT(11)      NOT NULL COMMENT '关联的 Agent',
    session_name   VARCHAR(300) NOT NULL COMMENT '会话名称',
    creator        VARCHAR(30)  NOT NULL COMMENT '创建者',
    last_question  VARCHAR(200) NULL COMMENT '最近一次提问',
    is_delete      TINYINT      NOT NULL DEFAULT 0 COMMENT '软删除标记：0-正常 1-已删除',
    PRIMARY KEY (session_id),
    KEY idx_agent_id (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话表';