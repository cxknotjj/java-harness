package com.dark.javaHarness.service.impl;

import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.domain.RouteDecision;
import com.dark.javaHarness.domain.dto.ChatRequest;
import com.dark.javaHarness.domain.dto.ChatResponse;
import com.dark.javaHarness.domain.dto.SseMeta;
import com.dark.javaHarness.enums.GoalStatus;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.ChatService;
import com.dark.javaHarness.service.RouteJudge;
import com.dark.javaHarness.service.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 聊天用例服务实现：承载聊天完整业务编排。
 * - 无 sessionId 时自动建档
 * - 同步聊天走 Agent 单次调用（executeSync）
 * - 流式聊天走响应式 ({@link #streamReactive})
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final String GENERAL_AGENT = "general";
    /** SSE 事件名：元数据（sessionId/goalId/status/newSession） */
    private static final String EVENT_META = "meta";
    /** SSE 事件名：异常 */
    private static final String EVENT_ERROR = "error";

    /** Jackson 序列化（SseMeta 为 record，默认序列化即可） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AgentService agentService;
    private final SessionService sessionService;
    private final RouteJudge routeJudge;

    public ChatServiceImpl(AgentService agentService, SessionService sessionService, RouteJudge routeJudge) {
        this.agentService = agentService;
        this.sessionService = sessionService;
        this.routeJudge = routeJudge;
    }

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    /** 同步聊天：无 sessionId 建档，调 general Agent 同步执行并写回会话记忆 */
    @Override
    public ChatResponse chat(ChatRequest request) {
        // 无 sessionId 时自动建档（session 表），会话名取首条提问
        String sessionId = request.sessionId();
        boolean newSession = false;
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = sessionService.createSession("anonymous", request.message());
            newSession = true;
        }

        // 主 Agent 前置判断：分流「简单(场景A)/复杂(场景B)」，仅打日志，不执行具体任务
        logRoute(request.message());

        Goal goal = agentService.executeSync(GENERAL_AGENT, request.message(), sessionId);
        writeBackContext(sessionId, request.message(), goal);

        if (goal.status() == GoalStatus.FAILED) {
            return ChatResponse.failure(sessionId, newSession, goal.id(), goal.summary());
        }
        return ChatResponse.success(sessionId, newSession, goal.id(), goal.summary());
    }

    /** 同步执行成功后写回会话记忆 */
    private void writeBackContext(String sessionId, String message, Goal goal) {
        if (sessionId != null && !sessionId.isBlank() && goal.status() == GoalStatus.SUCCEEDED) {
            sessionService.saveContext(sessionId, new UserMessage(message));
            sessionService.saveContext(sessionId, new AssistantMessage(goal.summary()));
            sessionService.touchSession(sessionId, message);
        }
    }

    /** 建会话所需的会话标识（sid + 是否新建） */
    private record SessionCtx(String sid, boolean newSession) {
    }

    /**
     * 响应式流式聊天：返回 text/event-stream 格式的 SSE 行文本。
     * - 无 sessionId 时在 boundedElastic 上自动建档
     * - 逐 token 产出 {@code data: <token>}，结束后产出 {@code data: [DONE]}，末尾产出 meta 事件
     * - agent 流出错时产出 error 事件 + 错误信息，并以 meta(FAILED) 收尾，避免调用方悬挂
     */
    @Override
    public Flux<String> streamReactive(ChatRequest request) {
        // 主 Agent 前置判断：分流决策（同步旁路，仅日志，不阻塞后续异步流主体）
        logRoute(request.message());

        String existing = request.sessionId();
        boolean needNew = existing == null || existing.isBlank();
        Mono<SessionCtx> sessionMono = needNew
                ? Mono.fromCallable(() -> sessionService.createSession("anonymous", request.message()))
                        .map(sid -> new SessionCtx(sid, true))
                        .subscribeOn(Schedulers.boundedElastic())
                : Mono.just(new SessionCtx(existing, false));

        return sessionMono.flatMapMany(ctx -> {
            // agentId 非空时按该 Agent 路由，否则走默认 Agent
            Flux<String> agentTokens = (request.agentId() != null)
                    ? agentService.executeStreamReactiveByAgentId(request.agentId(), request.message(), ctx.sid())
                    : agentService.executeStreamReactive(GENERAL_AGENT, request.message(), ctx.sid());
            // doOnNext 收集完整回复，流正常结束后由 doOnComplete 统一写回会话记忆（保持多轮记忆语义）
            StringBuilder full = new StringBuilder();
            Flux<String> body = agentTokens
                    .doOnNext(full::append)
                    .map(token -> "data: " + token)
                    .concatWithValues("data: [DONE]")
                    .concatWith(metaEvent(ctx.sid(), ctx.newSession(), GoalStatus.SUCCEEDED.name(), null))
                    .doOnComplete(() -> writeBackContext(ctx.sid(), request.message(), full.toString()))
                    .onErrorResume(ex -> {
                        String err = safeMessage(ex);
                        return Flux.concat(
                                Flux.just("event: " + EVENT_ERROR,
                                        "data: " + err),
                                metaEvent(ctx.sid(), ctx.newSession(), GoalStatus.FAILED.name(), err));
                    });
            return body;
        });
    }

    /** 流式成功后写回会话记忆（响应式路径：assistant 完整回复已由 doOnNext 收集） */
    private void writeBackContext(String sessionId, String message, String assistantReply) {
        if (sessionId != null && !sessionId.isBlank()) {
            sessionService.saveContext(sessionId, new UserMessage(message));
            sessionService.saveContext(sessionId, new AssistantMessage(assistantReply));
            sessionService.touchSession(sessionId, message);
        }
    }

    /** 组装 SSE meta 事件两行：{@code event: meta} + {@code data: {json}} */
    private Flux<String> metaEvent(String sessionId, boolean newSession, String status, String error) {
        SseMeta meta = new SseMeta(sessionId, newSession, null, status, error);
        try {
            return Flux.just("event: " + EVENT_META, "data: " + OBJECT_MAPPER.writeValueAsString(meta));
        } catch (Exception e) {
            return Flux.just("event: " + EVENT_META, "data: {\"error\":\"meta serialization failed\"}");
        }
    }

    /** 安全取异常信息，避免 getMessage 为空导致行文本不规范；换行替换为空格避免破坏逐行解析 */
    private static String safeMessage(Throwable ex) {
        String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return msg.replaceAll("[\\r\\n]+", " ");
    }

    /**
     * 主 Agent 前置判断的日志出口：调用 {@link RouteJudge} 得到分流决策并记录。
     * 当前只有日志与决策输出；复杂路径（多 Agent Graph）就绪后，
     * 在调用方据此切换执行链路。route 不写回会话、不改变对外契约。
     */
    private void logRoute(String message) {
        try {
            RouteDecision route = routeJudge.judge(message);
            log.info("[route] message '{}' -> {}", trimForLog(message), route);
        } catch (Exception e) {
            // 判断异常不得影响请求主流程
            log.warn("[route] 主 Agent 判断异常，忽略：{}", safeMessage(e));
        }
    }

    /** 截断过长的 message 用于日志，避免刷屏 */
    private static String trimForLog(String message) {
        if (message == null) {
            return "";
        }
        String single = message.replaceAll("[\\r\\n]+", " ");
        return single.length() > 80 ? single.substring(0, 80) + "..." : single;
    }
}