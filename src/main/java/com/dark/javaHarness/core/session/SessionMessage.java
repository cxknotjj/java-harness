package com.dark.javaHarness.core.session;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 会话消息实体，对应表 session_messages。
 * 与 session 一对一：每个会话仅一条记录，content 以 JSON 形式存储该会话的完整上下文。
 */
@Data
@TableName("session_messages")
public class SessionMessage {

    /** 消息唯一主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的会话ID（session.session_id 的字符串形式），一个会话仅对应本表一行 */
    private String sessionId;

    /** 租户隔离ID，用于SaaS多租户场景下的数据隔离 */
    private String tenantId;

    /** 消息角色：system / user / assistant / tool */
    private String role;

    /** 消息正文内容 */
    private String content;

    /** 消耗的 Token 数量 */
    private Integer tokenCount;

    /** 扩展元数据（JSON）：工具调用参数、API耗时、引用文档ID等动态扩展信息 */
    private String metadata;

    /** 消息创建时间戳，用于按时间顺序还原对话历史 */
    private LocalDateTime createdAt;
}
