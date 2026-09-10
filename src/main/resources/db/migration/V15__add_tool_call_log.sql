-- V15 - 工具调用观测表：记录每次真实工具执行的耗时与成败（HARNESS_TODO「工具调用与 MCP 日志记录」）。
-- 数据来源：ToolCallTracer 装饰器（执行后经 LlmCallRecorder 异步写入）。
-- 与 llm_call_log 同口径：观测失败不影响主链路；无 SSE emitter 的链路（QQ 渠道/API 直调）同样落库。
CREATE TABLE tool_call_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    session_id  VARCHAR(64)  NULL COMMENT '关联会话ID（无会话场景为空）',
    agent_name  VARCHAR(64)  NOT NULL COMMENT '调用方角色（lead/researcher/aggregator/general/qq-channel 等）',
    tool_name   VARCHAR(128) NOT NULL COMMENT '工具名',
    server_name VARCHAR(64)  NULL COMMENT '来源 MCP server 名（非 MCP 工具为空）',
    args_summary VARCHAR(512) NULL COMMENT '参数摘要（候选键值或截断原文）',
    status      VARCHAR(8)   NOT NULL COMMENT '结果：OK / ERROR',
    duration_ms BIGINT       NOT NULL COMMENT '调用耗时（毫秒）',
    error_msg   VARCHAR(512) NULL COMMENT '失败原因（status=ERROR 时）',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '调用结束时间',
    PRIMARY KEY (id),
    KEY idx_created_at (created_at),
    KEY idx_session_id (session_id),
    KEY idx_agent_name (agent_name),
    KEY idx_tool_name (tool_name),
    KEY idx_server_name (server_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工具调用观测日志表';
