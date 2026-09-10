package com.dark.javaHarness.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 工具调用观测实体，对应表 tool_call_log。
 * 由 LlmCallRecorder 在每次真实工具执行结束后异步写入。
 */
@Data
@TableName("tool_call_log")
public class ToolCallLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联会话ID（无会话场景为空） */
    private String sessionId;

    /** 调用方角色（lead/researcher/aggregator/general/qq-channel 等） */
    private String agentName;

    /** 工具名 */
    private String toolName;

    /** 来源 MCP server 名（非 MCP 工具为空） */
    private String serverName;

    /** 参数摘要（候选键值或截断原文） */
    private String argsSummary;

    /** 结果：OK / ERROR */
    private String status;

    /** 调用耗时（毫秒） */
    private Long durationMs;

    /** 失败原因（status=ERROR 时） */
    private String errorMsg;

    /** 调用结束时间 */
    private LocalDateTime createdAt;
}
