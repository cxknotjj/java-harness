-- ============================================================
-- javaHarness - Schema 初始化脚本
-- ============================================================

-- 目标表：持久化 Goal 的生命周期状态（由 GoalServiceImpl 读写）
CREATE TABLE IF NOT EXISTS goal (
    id           VARCHAR(64)  NOT NULL COMMENT '目标ID（goal-UUID）',
    objective    TEXT         NOT NULL COMMENT '目标描述',
    session_id   VARCHAR(64)  NULL COMMENT '关联会话ID，无会话记忆为空',
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED',
    summary      TEXT         NULL COMMENT '执行结论/结果摘要',
    created_at   DATETIME     NOT NULL COMMENT '创建时间',
    finished_at  DATETIME     NULL COMMENT '结束时间',
    PRIMARY KEY (id),
    KEY idx_status (status)
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

-- ============================================================
-- Agent 表：注册可用的 Agent，供路由与展示。
-- session.agent_id 关联本表 agent_id。
-- ============================================================
CREATE TABLE IF NOT EXISTS `agent` (
    agent_id     BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Agent 主键ID',
    agent_name   VARCHAR(64)  NOT NULL COMMENT 'Agent 名称（注册与路由用，如 general）',
    description  VARCHAR(500) NULL COMMENT 'Agent 描述',
    model        VARCHAR(64)  NULL COMMENT '绑定的模型名（如 qwen3.7-plus）',
    prompt       TEXT         NULL COMMENT '系统提示词（System Prompt）',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (agent_id),
    UNIQUE KEY uk_agent_name (agent_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 表';

-- ============================================================
-- 种子数据：注册对应的 Agent（见 ChatAgentConfig 中已实例化的 bean）。
-- 每个 agent_name 对应一个已实例化的 Agent：
--   general 走 DashScope（spring.ai.openai，模型由本表 model 决定）
--   deepseek 走独立端点（app.deepseek，模型由配置决定，本表 model 仅作展示）
-- INSERT IGNORE 利用唯一键保证幂等。
-- ============================================================
INSERT IGNORE INTO `agent` (agent_name, description, model, prompt, status) VALUES
('general', '通用 AI 助手（默认）', 'qwen-plus', '你是一个执行任务的 AI 助手，请直接给出简洁、可执行的完成结果。能结合会话历史。', 1),
('deepseek','深度推理助手（独立端点 DeepSeek）', 'deepseek-chat', '你是 DeepSeek 驱动的助手，给出生动且高质量的回复。', 1);

-- ============================================================
-- 模型-服务商映射表：驱动 ChatClientRegistry 动态注册。
-- ChatClientRegistry 启动时查询本表，按 model 字段把各模型绑定到对应服务商客户端，
-- 使“模型映射”可配置化（改库即生效，无需改代码）。
-- ============================================================
CREATE TABLE IF NOT EXISTS `model_provider` (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    model       VARCHAR(64)  NOT NULL COMMENT '模型名（agent.model 引用的值）',
    provider    VARCHAR(32)  NOT NULL COMMENT '服务商标识：dashscope / deepseek / ...',
    api_url     VARCHAR(255) NULL COMMENT '服务商端点 base-url（如 https://dashscope.aliyuncs.com/compatible-mode）',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_model (model)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型-服务商映射表';

INSERT IGNORE INTO `model_provider` (model, provider, api_url, status) VALUES
('qwen-plus',     'dashscope', 'https://dashscope.aliyuncs.com/compatible-mode', 1),
('qwen-turbo',    'dashscope', 'https://dashscope.aliyuncs.com/compatible-mode', 1),
('qwen-max',      'dashscope', 'https://dashscope.aliyuncs.com/compatible-mode', 1),
('qwen3.7-plus',  'dashscope', 'https://dashscope.aliyuncs.com/compatible-mode', 1),
('deepseek-chat', 'deepseek', 'https://api.deepseek.com', 1),
('deepseek-reasoner', 'deepseek', 'https://api.deepseek.com', 1),
('deepseek-v4-flash', 'deepseek', 'https://api.deepseek.com', 1);