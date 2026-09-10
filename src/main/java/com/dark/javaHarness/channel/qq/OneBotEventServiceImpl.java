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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上报事件处理实现：过滤 → 白名单/触发/限频 → 会话绑定 → 直调 ChatService → 分段回复。
 *
 * <p>会话键：私聊 {@code qq:private:<uid>}，群聊 {@code qq:group:<gid>:<uid>}
 * （群内按用户独立上下文）；binding 命中复用 harness 会话（多轮记忆跨消息延续），
 * 未命中经 {@code SessionService.createSession(creator=qq:<uid>, 首问)} 建档落库。
 * {@code /reset} 指令删除绑定行，下条消息开启新会话（多轮记忆清空）。
 *
 * <p>失败语义：任何异常/FAILED/超时只记日志不回复（渠道侧静默），不打断主流程、
 * 不向 QQ 侧泄错误细节；聊天超时后放弃回复但后台任务跑完仍落会话记忆。
 */
public class OneBotEventServiceImpl implements OneBotEventService {

    private static final Logger log = LoggerFactory.getLogger(OneBotEventServiceImpl.class);

    private static final AtomicLong CHAT_THREAD_SEQ = new AtomicLong();

    private final ChatService chatService;
    private final SessionService sessionService;
    private final OneBotSessionBindingMapper bindingMapper;
    private final NapCatApiClient apiClient;
    private final NapCatProperties props;
    private final UserRateLimiter rateLimiter;
    private final Set<Long> privateAllowUsers;
    private final ExecutorService chatExecutor;

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
        this.privateAllowUsers = parseAllowUsers(props.getPrivateAllowUsers());
        this.chatExecutor = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "onebot-chat-" + CHAT_THREAD_SEQ.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
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
        // 私聊白名单：名单非空时名单外用户直接丢弃（防陌生人刷 LLM token）
        if (!isGroup && !privateAllowUsers.isEmpty() && !privateAllowUsers.contains(event.userId())) {
            log.debug("[napcat] 私聊白名单外丢弃 uid={}", event.userId());
            return;
        }
        String text = isGroup ? extractGroupText(event) : extractPrivateText(event);
        if (text == null || text.isBlank()) {
            return;
        }
        // 同用户限频（群聊防风控 + 私聊防刷）：间隔内的消息直接丢弃
        if (!rateLimiter.tryAcquire(event.userId())) {
            log.debug("[napcat] 限频丢弃 uid={} gid={}", event.userId(), event.groupId());
            return;
        }
        // /reset 指令：删除会话绑定（下条消息开启新会话），不消耗 LLM
        if ("/reset".equalsIgnoreCase(text)) {
            resetSession(event);
            return;
        }
        Long sessionId = bindSession(event, text);
        ChatResponse resp;
        try {
            resp = chatWithTimeout(event, text, sessionId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (resp == null) {
            // 超时/执行异常：chatWithTimeout 内已记日志，静默返回
            return;
        }
        if (!"SUCCEEDED".equals(resp.status()) || resp.reply() == null || resp.reply().isBlank()) {
            log.warn("[napcat] 聊天未产出回复（status={}，messageId={}）", resp.status(), event.messageId());
            return;
        }
        sendReply(event, isGroup, resp.reply());
    }

    /** 聊天调用（带超时）：超时放弃回复且静默，后台任务跑完仅落会话记忆；超时秒数 0 = 不限制 */
    private ChatResponse chatWithTimeout(OneBotEvent event, String text, long sessionId)
            throws InterruptedException {
        ChatRequest request = new ChatRequest(text, String.valueOf(sessionId), props.getAgentId());
        int timeoutSeconds = props.getChatTimeoutSeconds();
        if (timeoutSeconds <= 0) {
            return chatService.chat(request);
        }
        Future<ChatResponse> future = chatExecutor.submit(() -> chatService.chat(request));
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(false);
            log.warn("[napcat] 聊天超时（>{}s），放弃回复（messageId={}，后台任务继续落记忆）",
                    timeoutSeconds, event.messageId());
            return null;
        } catch (ExecutionException e) {
            log.warn("[napcat] 聊天执行异常（messageId={}）", event.messageId(), e.getCause());
            return null;
        }
    }

    /** /reset：删除会话绑定行，下条消息重新建档开启新会话，并回复确认 */
    private void resetSession(OneBotEvent event) {
        String key = sessionKey(event);
        int deleted = bindingMapper.delete(new QueryWrapper<OneBotSessionBinding>().eq("session_key", key));
        log.info("[napcat] 会话重置 key={}（删除 {} 行绑定）", key, deleted);
        sendReply(event, "group".equals(event.messageType()), "已开启新对话，之前的记忆已清空。");
    }

    /** 解析私聊白名单 CSV → Set&lt;Long&gt;（空串/空白 = 不限制；非法项记 warn 跳过） */
    private static Set<Long> parseAllowUsers(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        Set<Long> ids = new HashSet<>();
        for (String token : csv.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(t));
            } catch (NumberFormatException e) {
                log.warn("[napcat] 私聊白名单非法项忽略：{}", t);
            }
        }
        return ids;
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
