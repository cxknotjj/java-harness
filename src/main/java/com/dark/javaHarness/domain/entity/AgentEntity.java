package com.dark.javaHarness.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * Agent 实体，对应表 agent。
 * 记录 Agent 名称、描述、绑定的模型与系统提示词。
 */
@Data
@TableName("agent")
public class AgentEntity {

    /** Agent 主键ID */
    @TableId(type = IdType.AUTO)
    private Long agentId;

    /** Agent 名称（注册与路由用，如 general） */
    private String agentName;

    /** Agent 描述 */
    private String description;

    /** 绑定的模型名（如 qwen3.7-plus） */
    private String model;

    /** 系统提示词（System Prompt） */
    private String prompt;

    /** 状态：1-启用 0-禁用 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
