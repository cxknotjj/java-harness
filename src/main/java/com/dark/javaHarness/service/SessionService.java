package com.dark.javaHarness.service;

import com.dark.javaHarness.domain.entity.SessionEntity;
import java.util.List;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

/**
 * 会话服务：管理多轮会话记忆（session + session_messages 两张表）。
 *
 * 同时实现 Spring AI {@link ChatMemory} 接口，使本服务可作为
 * {@code MessageChatMemoryAdvisor} 的记忆源被官方 Advisor 机制消费
 * （conversationId 对应本服务的 sessionId）。
 */
public interface SessionService extends ChatMemory {

    /**
     * 创建新会话，会话名取首条提问（截断）。
     * @return sessionId（session 表自增主键的字符串形式）
     */
    String createSession(String creator, String firstQuestion);

    /**
     * 读取会话完整上下文，还原为 Spring AI Message 列表。
     */
    List<Message> loadContext(String sessionId);

    /**
     * 追加保存单条会话消息（session_messages 与 session 一对一，该会话仅一行）。
     */
    void saveContext(String sessionId, Message message);

    /** 更新会话的最近一次提问 */
    void touchSession(String sessionId, String lastQuestion);

    /** 查询会话（软删除的不会返回） */
    SessionEntity getSession(String sessionId);
}