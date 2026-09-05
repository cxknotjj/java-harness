package com.dark.javaHarness.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dark.javaHarness.domain.entity.SessionEntity;
import java.util.List;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

/**
 * 会话服务：管理多轮会话记忆（session + session_messages 两张表）。
 *
 * 同时实现 Spring AI {@link ChatMemory} 接口：Agent 侧只读消费（get/loadContext），
 * 写入统一由 ChatService 在调用结束后负责（saveContext）——会话快照只存
 * user/assistant 两类角色，system 角色提示词不入库（避免角色错位）。
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

    /**
     * 分页查询会话（软删除的不会返回），按会话ID降序（最新在前）。
     * @param current 页码，从 1 开始
     * @param size    每页条数
     * @return MyBatis-Plus 分页结果（含总数与页数）
     */
    Page<SessionEntity> page(long current, long size);
}