package com.dark.javaHarness.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * MCP server 连接观测实体，对应表 mcp_server_log。
 * 由 McpServerRecorder 在每次连接/发现结束后异步写入。
 */
@Data
@TableName("mcp_server_log")
public class McpServerLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** MCP server 名（mcp-config.json 条目名或 default） */
    private String serverName;

    /** 传输方式：stdio / http */
    private String transport;

    /** 事件：CONNECTED-连接并发现完成 / FAILED-连接或发现失败 */
    private String event;

    /** 发现的工具数（FAILED 时为空） */
    private Integer toolCount;

    /** 失败原因（event=FAILED 时） */
    private String errorMsg;

    /** 事件时间 */
    private LocalDateTime createdAt;
}
