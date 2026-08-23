package com.dark.javaHarness.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 目标实体，对应表 goal。
 * status 存储 GoalStatus 枚举名（PENDING/RUNNING/SUCCEEDED/FAILED）。
 */
@Data
@TableName("goal")
public class GoalEntity {

    /** 目标ID（goal-序号，由服务生成） */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 目标描述（要完成的事） */
    private String objective;

    /** 关联会话ID，无会话记忆为空 */
    private String sessionId;

    /** 生命周期状态：PENDING/RUNNING/SUCCEEDED/FAILED */
    private String status;

    /** 执行摘要（成功时结果 / 失败时原因） */
    private String summary;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 完成时间（成功或失败时设置） */
    private LocalDateTime finishedAt;
}
