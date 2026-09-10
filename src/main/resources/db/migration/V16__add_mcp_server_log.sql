-- V16 - MCP server 连接观测表：记录每个 MCP server 的连接/发现事件（成功带工具数，失败带原因）。
-- 数据来源：McpToolProvider.connectAndDiscover（后台预热与请求期懒连接两条路径均覆盖），
-- 经 McpServerRecorder 异步写入；失败隔离语义不变，仅补结构化可查询记录。
CREATE TABLE mcp_server_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    server_name VARCHAR(64)  NOT NULL COMMENT 'MCP server 名（mcp-config.json 条目名或 default）',
    transport   VARCHAR(8)   NULL COMMENT '传输方式：stdio / http',
    event       VARCHAR(16)  NOT NULL COMMENT '事件：CONNECTED-连接并发现完成 / FAILED-连接或发现失败',
    tool_count  INT          NULL COMMENT '发现的工具数（FAILED 时为空）',
    error_msg   VARCHAR(512) NULL COMMENT '失败原因（event=FAILED 时）',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '事件时间',
    PRIMARY KEY (id),
    KEY idx_server_name (server_name),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP server 连接观测日志表';
