package com.dark.javaHarness.channel.qq;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dark.javaHarness.channel.qq.dto.MessageSegment;
import com.dark.javaHarness.channel.qq.dto.OneBotEvent;
import com.dark.javaHarness.domain.dto.ChatRequest;
import com.dark.javaHarness.domain.dto.ChatResponse;
import com.dark.javaHarness.domain.entity.OneBotSessionBinding;
import com.dark.javaHarness.mapper.OneBotSessionBindingMapper;
import com.dark.javaHarness.service.ChatService;
import com.dark.javaHarness.service.SessionService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上报事件处理实现：过滤 → 群聊触发/限频 → 会话绑定 → 直调 ChatService → 分段回复。
 *
 * <p>会话键：私聊 {@code qq:private:<uid>}，群聊 {@code qq:group:<gid>:<uid>}
 * （群内按用户独立上下文）；binding 命中复用 harness 会话（多轮记忆跨消息延续），
 * 未命中经 {@code SessionService.createSession(creator=qq:<uid>, 首问)} 建档落库。
 *
 * <p>失败语义：任何异常/FAILED 状态只记日志不回复（渠道侧静默），不打断主流程、
 * 不向 QQ 侧泄错误细节。
 */
public class OneBotEventServiceImpl implements OneBotEventService {

    private static final Logger log = LoggerFactory.getLogger(OneBotEventServiceImpl.class);

    private final ChatService chatService;
    private final SessionService sessionService;
    private final OneBotSessionBindingMapper bindingMapper;
    private final NapCatApiClient apiClient;
    private final NapCatProperties props;
    private final UserRateLimiter rateLimiter;

    public OneBotEventServiceImpl(ChatService chatService,
                                  SessionService sessionService,
                                  OneBotSessionBindingMapper bindingMapper,
                                  NapCatApiClient apiClient,
                                  NapCatProperties props) {
        this.chatService = chatService;
        this.sessionService = sessionService;
        this.bindingMapper = bindingMapper;
        this.apiClient = apiClient;
        this.props = props;
        this.rateLimiter = new UserRateLimiter(props.getRateLimit().getPerUserSeconds() * 1000L);
    }

    @Override
    public void handle(OneBotEvent event) {
        try {
            doHandle(event);
        } catch (Exception e) {
            log.error("[napcat] 事件处理失败（messageId={}）", event.messageId(), e);
        }
    }

    private void doHandle(OneBotEvent event) {
        // 自消息过滤（防自循环；reportSelfMessage=false 为另一道保险，缺一不可）
        if (Objects.equals(event.userId(), selfIdAsLong())) {
            return;
        }
        boolean isGroup = "group".equals(event.messageType());
        String text = isGroup ? extractGroupText(event) : extractPrivateText(event);
        if (text == null || text.isBlank()) {
            return;
        }
        // 群聊限频（防风控）：间隔内的同用户消息直接丢弃
        if (isGroup && !rateLimiter.tryAcquire(event.userId())) {
            log.debug("[napcat] 群聊限频丢弃 uid={} gid={}", event.userId(), event.groupId());
            return;
        }
        Long sessionId = bindSession(event, text);
        ChatResponse resp = chatService.chat(new ChatRequest(text, String.valueOf(sessionId), props.getAgentId()));
        if (resp == null || !"SUCCEEDED".equals(resp.status()) || resp.reply() == null
                || resp.reply().isBlank()) {
            log.warn("[napcat] 聊天未产出回复（status={}，messageId={}）",
                    resp == null ? "null" : resp.status(), event.messageId());
            return;
        }
        sendReply(event, isGroup, resp.reply());
    }

    /** binding 命中复用，未命中建 harness 会话并落库 */
    private Long bindSession(OneBotEvent event, String text) {
        String key = sessionKey(event);
        OneBotSessionBinding row = bindingMapper.selectOne(
                new QueryWrapper<OneBotSessionBinding>().eq("session_key", key));
        if (row != null) {
            return row.getSessionId();
        }
        long sessionId = Long.parseLong(sessionService.createSession("qq:" + event.userId(), text));
        LocalDateTime now = LocalDateTime.now();
        OneBotSessionBinding entity = new OneBotSessionBinding();
        entity.setSessionKey(key);
        entity.setSessionId(sessionId);
        entity.setQqUserId(String.valueOf(event.userId()));
        entity.setGroupId(event.groupId() == null ? null : String.valueOf(event.groupId()));
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        bindingMapper.insert(entity);
        log.info("[napcat] 新建会话绑定 key={} -> session={}", key, sessionId);
        return sessionId;
    }

    /** 私聊全文响应；正文取 message 数组 text 段，数组缺失兜底 raw_message */
    private String extractPrivateText(OneBotEvent event) {
        String joined = joinTextSegments(event.message());
        if (!joined.isBlank()) {
            return joined.trim();
        }
        return event.rawMessage() == null ? null : event.rawMessage().trim();
    }

    /**
     * 群聊触发判断：at=仅@机器人（message 数组 at 段为准，raw_message CQ 码兜底）；
     * prefix=命令前缀；all=全部响应。返回 null 表示不触发。
     */
    private String extractGroupText(OneBotEvent event) {
        String mode = props.getGroupTrigger().getMode();
        String joined = joinTextSegments(event.message());
        String selfId = props.getSelfId();
        switch (mode == null ? "at" : mode) {
            case "at" -> {
                if (!atSelf(event, selfId)) {
                    return null;
                }
                return joined.isBlank() ? null : joined.trim();
            }
            case "prefix" -> {
                String base = joined.isBlank() ? nullToEmpty(event.rawMessage()).trim() : joined.trim();
                String prefix = nullToEmpty(props.getGroupTrigger().getPrefix());
                if (base.isBlank()) {
                    return null;
                }
                if (!prefix.isBlank()) {
                    if (!base.startsWith(prefix)) {
                        return null;
                    }
                    base = base.substring(prefix.length()).trim();
                }
                return base.isBlank() ? null : base;
            }
            default -> {
                // all：全量响应
                String base = joined.isBlank() ? nullToEmpty(event.rawMessage()).trim() : joined.trim();
                return base.isBlank() ? null : base;
            }
        }
    }

    /** @ 触发判据：message 数组 at 段 data.qq == self-id；数组缺失时兜底 raw_message CQ 码 */
    private boolean atSelf(OneBotEvent event, String selfId) {
        if (selfId == null || selfId.isBlank()) {
            return false;
        }
        if (event.message() != null) {
            return event.message().stream().anyMatch(s -> "at".equals(s.type()) && selfId.equals(s.qq()));
        }
        return nullToEmpty(event.rawMessage()).contains("[CQ:at,qq=" + selfId + "]");
    }

    private String joinTextSegments(List<MessageSegment> segments) {
        if (segments == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (MessageSegment segment : segments) {
            if ("text".equals(segment.type())) {
                sb.append(segment.text());
            }
        }
        return sb.toString();
    }

    /** 回复发送：超长按段落边界分段；群聊首条带 reply 段引用原消息 */
    private void sendReply(OneBotEvent event, boolean isGroup, String reply) {
        List<String> chunks = splitReply(reply, props.getReply().getMaxLength());
        for (int i = 0; i < chunks.size(); i++) {
            List<MessageSegment> segments = new ArrayList<>();
            if (isGroup && i == 0 && event.messageId() != null) {
                segments.add(new MessageSegment("reply",
                        Map.of("message_id", event.messageId())));
            }
            segments.add(new MessageSegment("text", Map.of("text", chunks.get(i))));
            if (isGroup) {
                apiClient.sendGroupMsg(event.groupId(), segments);
            } else {
                apiClient.sendPrivateMsg(event.userId(), segments);
            }
        }
    }

    /**
     * 超长分段：优先按空行段落边界打包（段间距保留在段内），单段仍超长再按字符硬切。
     * max &lt;= 0 表示不限制（整段一条发送）。
     */
    static List<String> splitReply(String text, int max) {
        String trimmed = text == null ? "" : text.trim();
        if (max <= 0 || trimmed.length() <= max) {
            return List.of(trimmed);
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : trimmed.split("\n\n", -1)) {
            String p = paragraph;
            while (p.length() > max) {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                parts.add(p.substring(0, max));
                p = p.substring(max);
            }
            if (p.isEmpty()) {
                continue;
            }
            if (current.isEmpty()) {
                current.append(p);
            } else if (current.length() + 2 + p.length() <= max) {
                current.append("\n\n").append(p);
            } else {
                parts.add(current.toString());
                current.setLength(0);
                current.append(p);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts.isEmpty() ? List.of(trimmed) : parts;
    }

    private String sessionKey(OneBotEvent event) {
        return "group".equals(event.messageType())
                ? "qq:group:" + event.groupId() + ":" + event.userId()
                : "qq:private:" + event.userId();
    }

    private Long selfIdAsLong() {
        try {
            return props.getSelfId() == null ? null : Long.valueOf(props.getSelfId());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
