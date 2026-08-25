package com.dark.javaHarness.service.impl;

import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.domain.dto.ChatRequest;
import com.dark.javaHarness.domain.dto.ChatResponse;
import com.dark.javaHarness.domain.dto.SseMeta;
import com.dark.javaHarness.enums.ExecutionType;
import com.dark.javaHarness.enums.GoalStatus;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.ChatService;
import com.dark.javaHarness.service.SessionService;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 聊天用例服务实现：承载聊天完整业务编排。
 * - 无 sessionId 时自动建档
 * - 调 general Agent 同步/流式执行（goal 生命周期与 summary 留存）
 * - 组装 ChatResponse
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final String GENERAL_AGENT = "general";
    /** SSE 事件名：元数据（sessionId/goalId/status/newSession） */
    private static final String EVENT_META = "meta";
    /** SSE 事件名：异常 */
    private static final String EVENT_ERROR = "error";

    private final AgentService agentService;
    private final SessionService sessionService;

    public ChatServiceImpl(AgentService agentService, SessionService sessionService) {
        this.agentService = agentService;
        this.sessionService = sessionService;
    }

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    /** 处理一次聊天请求（默认同步执行） */
    @Override
    public ChatResponse chat(ChatRequest request) {
        return chat(request, ExecutionType.SYNC);
    }

    /** 处理一次聊天请求，按执行类型走同步或流式 Agent 执行并组装响应 */
    @Override
    public ChatResponse chat(ChatRequest request, ExecutionType type) {
        // 无 sessionId 时自动建档（session 表），会话名取首条提问
        String sessionId = request.sessionId();
        boolean newSession = false;
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = sessionService.createSession("anonymous", request.message());
            newSession = true;
        }

        Goal goal = switch (type) {
            case SYNC -> executeSyncBySession(sessionId, request.message());
            case STREAM -> executeStreamBySession(sessionId, request.message(), request.agentId(), ignored -> { });
        };

        if (goal.status() == GoalStatus.FAILED) {
            return ChatResponse.failure(sessionId, newSession, goal.id(), goal.summary());
        }
        return ChatResponse.success(sessionId, newSession, goal.id(), goal.summary());
    }

    /** 同步执行（带会话记忆），成功后写回会话记忆（与流式路径保持一致） */
    private Goal executeSyncBySession(String sessionId, String message) {
        Goal goal = sessionId == null || sessionId.isBlank()
                ? agentService.executeSync(GENERAL_AGENT, message)
                : agentService.executeSync(GENERAL_AGENT, message, sessionId);
        writeBackContext(sessionId, message, goal);
        return goal;
    }

    /**
     * 流式执行（丢弃 token）：仅供 chat(..., STREAM) 收集完整结果后写回会话记忆。
     */
    private Goal executeStreamBySession(String sessionId, String message) {
        return executeStreamBySession(sessionId, message, null, ignored -> { });
    }

    /**
     * 流式执行并写回会话记忆：onToken 逐 token 消费（如 SSE 推送），
     * 流结束后统一写回 user/assistant，保证会话记忆与最终回复一致。
     * agentId 非空时按该 Agent 路由，否则走默认 Agent。
     */
    private Goal executeStreamBySession(String sessionId, String message, Long agentId, Consumer<String> onToken) {
        Goal goal = (agentId != null)
                ? agentService.executeStreamByAgentId(agentId, message, sessionId, onToken)
                : agentService.executeStream(GENERAL_AGENT, message, sessionId, onToken);
        log.info("流式执行路由: agentId={} -> goalId={}, status={}", agentId, goal.id(), goal.status());
        writeBackContext(sessionId, message, goal);
        return goal;
    }

    /** 流执行成功后写回会话记忆 */
    private void writeBackContext(String sessionId, String message, Goal goal) {
        if (sessionId != null && !sessionId.isBlank() && goal.status() == GoalStatus.SUCCEEDED) {
            sessionService.saveContext(sessionId, new UserMessage(message));
            sessionService.saveContext(sessionId, new AssistantMessage(goal.summary()));
            sessionService.touchSession(sessionId, message);
        }
    }

    /** 流式聊天（SSE）：逐 token 推送，结束发 [DONE] 和 meta 事件；无 sessionId 时自动建档 */
    @Override
    public SseEmitter stream(ChatRequest request) {
        // 0L = 不超时，长连接直到流结束主动关闭
        SseEmitter emitter = new SseEmitter(0L);
        log.info("收到流式聊天请求: message='{}', sessionId={}, agentId={}",
                request.message(), request.sessionId(), request.agentId());

        // 无 sessionId 时自动建档（session 表），会话名取首条提问
        String sessionId = request.sessionId();
        boolean newSession = false;
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = sessionService.createSession("anonymous", request.message());
            newSession = true;
        }
        final String sid = sessionId;
        final boolean isNew = newSession;

        CompletableFuture.runAsync(() -> {
            StringBuilder full = new StringBuilder();
            try {
                // 逐 token 推送，流结束后由 executeStreamBySession 统一写回会话记忆
                Goal goal = executeStreamBySession(sid, request.message(), request.agentId(),
                        token -> {
                            full.append(token);
                            try {
                                emitter.send(SseEmitter.event().data(token));
                            } catch (Exception e) {
                                throw new IllegalStateException("SSE 发送失败", e);
                            }
                        });

                // 发送 DONE + meta 元数据（失败时附带原因）
                emitter.send(SseEmitter.event().data("[DONE]"));
                SseMeta meta = new SseMeta(
                        sid,
                        isNew,
                        goal.id(),
                        goal.status().name(),
                        goal.status() == GoalStatus.FAILED ? goal.summary() : null);
                emitter.send(SseEmitter.event().name(EVENT_META).data(meta));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name(EVENT_ERROR).data(e.getMessage()));
                } catch (Exception ignored) {
                    // 连接可能已断，忽略
                }
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}