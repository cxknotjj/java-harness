package com.dark.javaHarness.core.session;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

/**
 * 会话记忆存储：基于 session + session_messages 两张表实现多轮会话记忆。
 *
 * 存储模型（上下文快照式，session_messages 与 session 一对一）：
 * - session：会话主表（名称/创建者/最近提问/软删除）
 * - session_messages：每个会话仅一行，content 字段以 JSON 形式存储该会话的
 *   【完整会话上下文】：
 *   [{"role":"user","content":"..."},{"role":"assistant","content":"..."},...]
 *   每条新消息追加进该行 content；读取时还原全部历史。
 *
 * sessionId 使用 session 表自增主键的字符串形式，与 session_messages.session_id（varchar）对齐。
 */
@Service
public class SessionMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryStore.class);

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

    public SessionMemoryStore(SessionMapper sessionMapper,
                              SessionMessageMapper messageMapper,
                              ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建新会话，会话名取首条提问（截断）。
     * @return sessionId（session 表自增主键的字符串形式）
     */
    public String createSession(String creator, String firstQuestion) {
        Session session = new Session();
        session.setAgentId(DEFAULT_AGENT_ID);
        session.setSessionName(truncate(firstQuestion, SESSION_NAME_MAX));
        session.setCreator(creator == null || creator.isBlank() ? "anonymous" : creator);
        session.setLastQuestion(truncate(firstQuestion, 200));
        session.setIsDelete(0);
        sessionMapper.insert(session);
        log.info("创建会话 sessionId={}, name='{}'", session.getSessionId(), session.getSessionName());
        return String.valueOf(session.getSessionId());
    }

    /**
     * 读取会话完整上下文：取 session_messages 中该会话的上下文行
     * （一对一，仅一条；查询保留取最新一条以兼容历史多行数据），
     * 解析 content 里的 JSON 数组还原为 Spring AI Message 列表。
     */
    public List<Message> loadContext(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        QueryWrapper<SessionMessage> qw = new QueryWrapper<>();
        qw.eq("session_id", sessionId)
                .orderByDesc("id")
                .last("LIMIT 1");
        SessionMessage latest = messageMapper.selectOne(qw);
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

    /**
     * 追加保存单条会话消息：session_messages 与 session 一对一，该会话仅一行。
     * 将本条消息追加进已有上下文 JSON 后整体覆盖写回该行；尚无记录时首次插入。
     * content 始终保存完整上下文：
     * [{"role":"user","content":"..."},{"role":"assistant","content":"..."},...]
     */
    public void saveContext(String sessionId, Message message) {
        if (sessionId == null || sessionId.isBlank() || message == null) {
            return;
        }
        // 查询该会话唯一的上下文行（保留取最新一条以兼容历史多行数据）
        QueryWrapper<SessionMessage> qw = new QueryWrapper<>();
        qw.eq("session_id", sessionId)
                .orderByDesc("id")
                .last("LIMIT 1");
        SessionMessage existing = messageMapper.selectOne(qw);

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
            UpdateWrapper<SessionMessage> uw = new UpdateWrapper<>();
            uw.eq("id", existing.getId())
                    .set("content", json);
            messageMapper.update(null, uw);
        } else {
            SessionMessage row = new SessionMessage();
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
    public void touchSession(String sessionId, String lastQuestion) {
        Long sid = parseSessionId(sessionId);
        if (sid == null) {
            return;
        }
        UpdateWrapper<Session> uw = new UpdateWrapper<>();
        uw.eq("session_id", sid)
                .set("last_question", truncate(lastQuestion, 200));
        sessionMapper.update(null, uw);
    }

    /** 查询会话（软删除的不会返回） */
    public Session getSession(String sessionId) {
        Long sid = parseSessionId(sessionId);
        if (sid == null) {
            return null;
        }
        QueryWrapper<Session> qw = new QueryWrapper<>();
        qw.eq("session_id", sid);
        return sessionMapper.selectOne(qw);
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

    /** 由 role 字符串还原 Spring AI Message */
    private Message toMessage(String role, String content) {
        return switch (role == null ? "user" : role) {
            case "assistant" -> new AssistantMessage(content);
            case "system" -> new SystemMessage(content);
            default -> new UserMessage(content);
        };
    }

    /** 由 Spring AI Message 得到 role 字符串 */
    private String roleOf(Message message) {
        if (message instanceof AssistantMessage) {
            return "assistant";
        }
        if (message instanceof SystemMessage) {
            return "system";
        }
        return "user";
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
