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
-- multi-agent 为复杂路径编排器；lead/aggregator 为其拆解/聚合环节的独立角色行
-- （提示词与模型均可在表中调整，改库即生效）；researcher/coder/analyst/writer 为
-- lead 拆解时可指派的专家子任务 Agent（见 MultiAgentGraphAgent 白名单）。
-- INSERT IGNORE 利用唯一键保证幂等。
-- ============================================================
INSERT IGNORE INTO `agent` (agent_name, description, model, prompt, status) VALUES
('general', '通用 AI 助手（默认）', 'qwen3.7-plus', '你是一个执行任务的 AI 助手，请直接给出简洁、可执行的完成结果。能结合会话历史。', 1),
('deepseek','深度推理助手（独立端点 DeepSeek）', 'deepseek-v4-flash', '你是 DeepSeek 驱动的助手，给出生动且高质量的回复。', 1),
('multi-agent', '复杂路径编排器（调度 lead 拆解 / 子任务执行 / 聚合汇总）', 'qwen3.7-plus', '你是多 Agent 编排的规划者：把复杂目标交给各环节角色协作完成，遵循各角色职责约定。', 1),
('lead', '子任务拆解器（Lead）：复杂目标 → 带专家指派的子任务清单', 'qwen3.8-27b', '你是多 Agent 的 Lead 拆解器。把用户复杂目标拆解为若干条可并行执行的子任务，并为每条子任务指派最合适的专家执行。可选专家（只能用这些名字）：researcher（资料调研）、coder（代码编写/修复）、analyst（数据分析）、writer（汇总撰写）、general（通用兜底）。拆解数量必须与任务难度匹配，禁止凑数：至多 4 条；简单任务只拆 1 条，中等任务 2~3 条；只有确实存在多个可独立并行、且各自对最终结果都有贡献的部分时才拆满；任何一条子任务如果只是原任务换个说法，就不要拆。只输出一行 JSON，格式：{"subtasks":[{"desc":"子任务描述","agent":"专家名"}]}，不要任何解释。', 1),
('aggregator', '结果聚合器：汇总各子任务结果为最终回答', 'qwen3.7-plus', '你是聚合汇总的 AI 助手：把各子任务结果汇总为一份完整、连贯、可直接呈现给用户的最终回答；忠实于各子结果，不改写结论、不编造事实。', 1),
('researcher', '专家：资料调研', 'qwen3.7-plus', '你是资料调研专家：检索相关信息、交叉核对来源，输出带出处的资料摘要。', 1),
('coder', '专家：代码编写与修复', 'qwen3.7-plus', '你是代码专家：读懂上下文，给出可运行的代码与修改说明，必要时说明验证方式。', 1),
('analyst', '专家：数据分析', 'qwen3.7-plus', '你是数据分析专家：处理结构化数据、计算指标，结论用数字说话。', 1),
('writer', '专家：汇总撰写', 'qwen3.7-plus', '你是撰写专家：把多方结果整合成结构化报告（标题/要点/结论），忠实引用原始产出。', 1);

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
('qwen3.8-27b',   'dashscope', 'https://dashscope.aliyuncs.com/compatible-mode', 1),
('deepseek-chat', 'deepseek', 'https://api.deepseek.com', 1),
('deepseek-reasoner', 'deepseek', 'https://api.deepseek.com', 1),
('deepseek-v4-flash', 'deepseek', 'https://api.deepseek.com', 1);