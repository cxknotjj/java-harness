package com.dark.javaHarness.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * OneBot QQ 会话与 harness 会话绑定，对应表 onebot_session_binding（MySQL 主库）。
 * 由 OneBotEventServiceImpl 维护：同一 session_key（私聊 uid / 群聊 gid+uid）首次
 * 出现时建 harness 会话并落库，后续消息复用该会话延续多轮记忆。
 */
@Data
@TableName("onebot_session_binding")
public class OneBotSessionBinding {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话键：qq:private:<uid> 或 qq:group:<gid>:<uid>，唯一 */
    private String sessionKey;

    /** session 表自增主键（DTO 层以字符串交换） */
    private Long sessionId;

    private String qqUserId;

    /** 群聊才有，私聊为 null */
    private String groupId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
