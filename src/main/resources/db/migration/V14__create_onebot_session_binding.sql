-- V14 - QQ 渠道接入：OneBot QQ 会话与 harness 会话绑定表。
-- 私聊 key = qq:private:<uid>；群聊 key = qq:group:<gid>:<uid>（群内按用户独立上下文）。
-- session_key 唯一：同一 QQ 上下文复用同一 harness 会话（多轮记忆跨消息延续）。
CREATE TABLE onebot_session_binding (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_key VARCHAR(128) NOT NULL COMMENT 'qq:private:<uid> 或 qq:group:<gid>:<uid>',
    session_id  BIGINT       NOT NULL COMMENT 'session 表自增主键（DTO 层以字符串交换）',
    qq_user_id  VARCHAR(32)  NOT NULL,
    group_id    VARCHAR(32)  NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_session_key (session_key)
) COMMENT 'OneBot QQ会话与 harness 会话绑定';
