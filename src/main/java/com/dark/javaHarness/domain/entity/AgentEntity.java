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

    /** 绑定的部署模型（model_provider.id）：精确表达"该 Agent 用哪个端点的哪个模型" */
    private Long modelProviderId;

    /** 系统提示词（System Prompt） */
    private String prompt;

    /** 分配的工具（逗号分隔：组名 web/demo/sandbox.* 或精确工具名；NULL/空白回退代码内置分配） */
    private String tools;

    /** 状态：1-启用 0-禁用 */
    private Integer status;

    /** 内部角色标志：1-编排内部角色（不作为对话 Agent 注册） 0-对话 Agent */
    private Integer isInternal;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
