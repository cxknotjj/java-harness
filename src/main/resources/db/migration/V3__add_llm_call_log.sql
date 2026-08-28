-- V3 - LLM 调用观测表：记录每次 LLM 调用的耗时与 token 消耗（HARNESS_TODO「链路追踪」观测层）。
-- 数据来源：AgentChatCaller（路径 B 各环节）/ GeneralAssistantAgent（路径 A）/ LlmRouteJudge（前置判断）
-- 统一经 LlmCallRecorder 异步写入；观测失败不影响主链路。
CREATE TABLE llm_call_log (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    session_id        VARCHAR(64)  NULL COMMENT '关联会话ID（路由判断等无会话场景为空）',
    agent_name        VARCHAR(64)  NOT NULL COMMENT '调用方角色（lead/researcher/aggregator/general/route-judge 等）',
    model             VARCHAR(64)  NULL COMMENT '实际使用的模型名',
    call_kind         VARCHAR(8)   NOT NULL COMMENT '调用形态：SYNC-阻塞 / STREAM-流式',
    status            VARCHAR(8)   NOT NULL COMMENT '结果：OK / ERROR',
    prompt_tokens     INT          NULL COMMENT '输入 token 数（流式为近似估算）',
    completion_tokens INT          NULL COMMENT '输出 token 数（流式为近似估算）',
    total_tokens      INT          NULL COMMENT '总 token 数',
    tokens_estimated  TINYINT      NOT NULL DEFAULT 0 COMMENT 'token 是否为近似估算：1-是（流式无 usage 回包）',
    duration_ms       BIGINT       NOT NULL COMMENT '调用耗时（毫秒）',
    error_msg         VARCHAR(512) NULL COMMENT '失败原因（status=ERROR 时）',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '调用结束时间',
    PRIMARY KEY (id),
    KEY idx_created_at (created_at),
    KEY idx_session_id (session_id),
    KEY idx_agent_name (agent_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM 调用观测日志表';
