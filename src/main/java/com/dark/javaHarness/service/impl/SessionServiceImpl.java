package com.dark.javaHarness.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dark.javaHarness.domain.entity.SessionEntity;
import com.dark.javaHarness.domain.entity.SessionMessageEntity;
import com.dark.javaHarness.mapper.SessionMapper;
import com.dark.javaHarness.mapper.SessionMessageMapper;
import com.dark.javaHarness.service.SessionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

/**
 * 会话服务实现：基于 session + session_messages 两张表实现多轮会话记忆。
 *
 * 存储模型（上下文快照式，session_messages 与 session 一对一）：
 * - session：会话主表（名称/创建者/最近提问/软删除）
 * - session_messages：每个会话仅一行，content 字段以 JSON 形式存储该会话的
 *   【完整会话上下文】：
 *   [{"role":"user","content":"..."},{"role":"assistant","content":"..."},...]
 *   每条新消息追加进该行 content；读取时还原全部历史。
 *   只存 user/assistant 两类角色：system 角色提示词每次请求现组装，一旦落库，
 *   旧提示词会在后续轮次抢占上下文头部，导致角色错位。
 *
 * sessionId 使用 session 表自增主键的字符串形式，与 session_messages.session_id（varchar）对齐。
 */
@Service
public class SessionServiceImpl implements SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionServiceImpl.class);

    /** 默认租户（未做多租户前统一使用） */
    private static final String DEFAULT_TENANT = "default";
    /** 快照行的角色标记：content 存的是全量上下文 JSON */
    private static final String ROLE_CONTEXT = "context";
    /** 默认 Agent 编号（Agent 尚无编号体系，先固定登记） */
    private static final int DEFAULT_AGENT_ID = 1;
    /** 会话名称最大长度（取首条提问截断） */
    private static final int SESSION_NAME_MAX = 100;

    private final SessionMapper sessionMapper;
    private final SessionMessageMapper messageMapper;
    private final ObjectMapper objectMapper;

    public SessionServiceImpl(SessionMapper sessionMapper,
                              SessionMessageMapper messageMapper,
                              ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
    }

    /** 创建新会话（会话名取首条提问截断），返回自增主键的字符串形式 */
    @Override
    public String createSession(String creator, String firstQuestion) {
        SessionEntity session = new SessionEntity();
        session.setAgentId(DEFAULT_AGENT_ID);
        session.setSessionName(truncate(firstQuestion, SESSION_NAME_MAX));
        session.setCreator(creator == null || creator.isBlank() ? "anonymous" : creator);
        session.setLastQuestion(truncate(firstQuestion, 200));
        session.setIsDelete(0);
        sessionMapper.insert(session);
        log.info("创建会话 sessionId={}, name='{}'", session.getSessionId(), session.getSessionName());
        return String.valueOf(session.getSessionId());
    }

    /** 读取会话完整上下文，还原为 Spring AI Message 列表（无会话或解析失败返回空列表） */
    @Override
    public List<Message> loadContext(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        QueryWrapper<SessionMessageEntity> qw = new QueryWrapper<>();
        qw.eq("session_id", sessionId)
                .orderByDesc("id")
                .last("LIMIT 1");
        SessionMessageEntity latest = messageMapper.selectOne(qw);
        if (latest == null || latest.getContent() == null || latest.getContent().isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, String>> items = objectMapper.readValue(
                    latest.getContent(), new TypeReference<List<Map<String, String>>>() {});
            List<Message> messages = new ArrayList<>(items.size());
            for (Map<String, String> item : items) {
                messages.add(toMessage(item.get("role"), item.get("content")));
            }
            return messages;
        } catch (JsonProcessingException e) {
            log.warn("解析会话上下文快照失败 sessionId={}，按空上下文处理", sessionId, e);
            return List.of();
        }
    }

    /** 追加保存单条会话消息到该会话唯一一行上下文（不存在则新建） */
    @Override
    public void saveContext(String sessionId, Message message) {
        if (sessionId == null || sessionId.isBlank() || message == null) {
            return;
        }
        // 查询该会话唯一的上下文行（保留取最新一条以兼容历史多行数据）
        QueryWrapper<SessionMessageEntity> qw = new QueryWrapper<>();
        qw.eq("session_id", sessionId)
                .orderByDesc("id")
                .last("LIMIT 1");
        SessionMessageEntity existing = messageMapper.selectOne(qw);

        // 读取已有上下文，追加本条消息
        List<Map<String, String>> items = new ArrayList<>();
        if (existing != null && existing.getContent() != null && !existing.getContent().isBlank()) {
            try {
                items = objectMapper.readValue(existing.getContent(),
                        new TypeReference<List<Map<String, String>>>() {});
            } catch (JsonProcessingException e) {
                log.warn("解析会话上下文快照失败 sessionId={}，按空上下文处理", sessionId, e);
            }
        }
        Map<String, String> item = new LinkedHashMap<>();
        item.put("role", roleOf(message));
        item.put("content", message.getText());
        items.add(item);

        String json;
        try {
            json = objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            log.error("序列化会话上下文失败 sessionId={}", sessionId, e);
            return;
        }
        if (existing != null) {
            UpdateWrapper<SessionMessageEntity> uw = new UpdateWrapper<>();
            uw.eq("id", existing.getId())
                    .set("content", json);
            messageMapper.update(null, uw);
        } else {
            SessionMessageEntity row = new SessionMessageEntity();
            row.setSessionId(sessionId);
            row.setTenantId(DEFAULT_TENANT);
            row.setRole(ROLE_CONTEXT);
            row.setContent(json);
            row.setTokenCount(0);
            row.setCreatedAt(LocalDateTime.now());
            messageMapper.insert(row);
        }
    }

    /** 更新会话的最近一次提问 */
    @Override
    public void touchSession(String sessionId, String lastQuestion) {
        Long sid = parseSessionId(sessionId);
        if (sid == null) {
            return;
        }
        UpdateWrapper<SessionEntity> uw = new UpdateWrapper<>();
        uw.eq("session_id", sid)
                .set("last_question", truncate(lastQuestion, 200));
        sessionMapper.update(null, uw);
    }

    /** 查询会话（非法或空 sessionId 返回 null） */
    @Override
    public SessionEntity getSession(String sessionId) {
        Long sid = parseSessionId(sessionId);
        if (sid == null) {
            return null;
        }
        QueryWrapper<SessionEntity> qw = new QueryWrapper<>();
        qw.eq("session_id", sid);
        return sessionMapper.selectOne(qw);
    }

    /** 分页查询会话（软删除的不会返回），按会话ID降序（最新在前） */
    @Override
    public Page<SessionEntity> page(long current, long size) {
        QueryWrapper<SessionEntity> qw = new QueryWrapper<>();
        qw.orderByDesc("session_id");
        return sessionMapper.selectPage(new Page<>(Math.max(current, 1), Math.max(size, 1)), qw);
    }

    /* ---------------- Spring AI ChatMemory 接口实现（包装现有逻辑） ---------------- */

    /** ChatMemory.get：按会话ID读取历史，等价于 {@link #loadContext}。 */
    @Override
    public List<Message> get(String conversationId) {
        return loadContext(conversationId);
    }

    /** ChatMemory.add：追加一组消息到指定会话，等价于逐个 {@link #saveContext}。 */
    @Override
    public void add(String conversationId, List<Message> messages) {
        for (Message m : messages) {
            saveContext(conversationId, m);
        }
    }

    /** ChatMemory.clear：清空指定会话的历史上下文（删除 session_messages 该会话行）。 */
    @Override
    public void clear(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        QueryWrapper<SessionMessageEntity> qw = new QueryWrapper<>();
        qw.eq("session_id", conversationId);
        messageMapper.delete(qw);
        log.info("清空会话上下文 sessionId={}", conversationId);
    }

    /** 字符串 sessionId 转 Long（session 表主键为 BIGINT；非法格式返回 null 并告警） */
    private Long parseSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(sessionId.trim());
        } catch (NumberFormatException e) {
            log.warn("非法 sessionId 格式: '{}'，无法查询 session 表", sessionId);
            return null;
        }
    }

    /** 由 role 字符串还原 Spring AI Message（会话快照只存 user/assistant，未知角色按 user 还原） */
    private Message toMessage(String role, String content) {
        return "assistant".equals(role) ? new AssistantMessage(content) : new UserMessage(content);
    }

    /** 由 Spring AI Message 得到 role 字符串（system 角色提示词不入库：每次请求现组装，落库会造成角色错位） */
    private String roleOf(Message message) {
        return message instanceof AssistantMessage ? "assistant" : "user";
    }

    /** 字符串截断到指定最大长度（null 返回空串） */
    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}