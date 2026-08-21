package com.dark.javaHarness.core.memory;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

/**
 * 会话记忆存储：基于 MySQL（MyBatis-Plus）实现 Spring AI 的 ChatMemory。
 *
 * 每个会话（sessionId）的消息按时间顺序保存，实现多轮会话记忆。
 * 实现 ChatMemory 接口后，可直接配合 Spring AI 的 ChatMemoryAdvisor 使用。
 */
@Service
public class ChatMemoryStore implements ChatMemory {

    private final ChatMemoryEntryMapper mapper;

    public ChatMemoryStore(ChatMemoryEntryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        for (Message msg : messages) {
            ChatMemoryEntry entry = new ChatMemoryEntry();
            entry.setSessionId(conversationId);
            entry.setRole(roleOf(msg));
            entry.setContent(msg.getText());
            entry.setCreatedAt(LocalDateTime.now());
            mapper.insert(entry);
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        QueryWrapper<ChatMemoryEntry> qw = new QueryWrapper<>();
        qw.eq("session_id", conversationId)
                .orderByAsc("created_at");
        return mapper.selectList(qw).stream()
                .map(this::toMessage)
                .collect(Collectors.toList());
    }

    @Override
    public void clear(String conversationId) {
        QueryWrapper<ChatMemoryEntry> qw = new QueryWrapper<>();
        qw.eq("session_id", conversationId);
        mapper.delete(qw);
    }

    /** 由 Spring AI Message 推断 role 字符串 */
    private String roleOf(Message msg) {
        if (msg instanceof UserMessage) {
            return "user";
        }
        if (msg instanceof AssistantMessage) {
            return "assistant";
        }
        if (msg instanceof SystemMessage) {
            return "system";
        }
        return "user";
    }

    /** 从数据库条目还原 Spring AI Message */
    private Message toMessage(ChatMemoryEntry entry) {
        return switch (entry.getRole()) {
            case "assistant" -> new AssistantMessage(entry.getContent());
            case "system" -> new SystemMessage(entry.getContent());
            default -> new UserMessage(entry.getContent());
        };
    }
}