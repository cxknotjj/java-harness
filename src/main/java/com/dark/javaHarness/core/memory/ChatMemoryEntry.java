package com.dark.javaHarness.core.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 会话记忆条目，对应表 chat_memory。
 * 按 sessionId 分组保存多轮对话消息。
 */
@Data
@TableName("chat_memory")
public class ChatMemoryEntry {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private String role;
    private String content;
    private LocalDateTime createdAt;
}