package com.dark.javaHarness.service.impl;

import com.dark.javaHarness.agent.ProgressLine;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.domain.RouteDecision;
import com.dark.javaHarness.domain.dto.ChatRequest;
import com.dark.javaHarness.domain.dto.ChatResponse;
import com.dark.javaHarness.domain.dto.SseMeta;
import com.dark.javaHarness.enums.AgentConstants;
import com.dark.javaHarness.enums.GoalStatus;
import com.dark.javaHarness.enums.SseProtocol;
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

    /** SSE 事件名/结束标记等协议常量统一在 {@link SseProtocol}（与 CLI 端共用） */

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

        // 主 Agent 前置判断：分流「场景A简单(general) / 场景B复杂(multi-agent)」
        String resolvedAgent = resolveAgent(request.message());

        Goal goal = agentService.executeSync(resolvedAgent, request.message(), sessionId);
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
        // 主 Agent 前置判断：分流「场景A简单(general) / 场景B复杂(multi-agent)」
        String resolvedAgent = resolveAgent(request.message());

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
                    : agentService.executeStreamReactive(resolvedAgent, request.message(), ctx.sid());
            // doOnNext 收集完整回复，流正常结束后由 doOnComplete 统一写回会话记忆（保持多轮记忆语义）
            // 其中「进度行」（以 ProgressLine.MARK 开头，多 Agent 编排的阶段反馈）不计入会话摘要
            StringBuilder full = new StringBuilder();
            Flux<String> body = agentTokens
                    .doOnNext(row -> { if (!ProgressLine.isProgress(row)) { full.append(row); } })
                    .flatMap(ChatServiceImpl::toSseRows)
                    .concatWithValues("data: " + SseProtocol.DONE_MARKER)
                    .concatWith(metaEvent(ctx.sid(), ctx.newSession(), GoalStatus.SUCCEEDED.name(), null))
                    .doOnComplete(() -> writeBackContext(ctx.sid(), request.message(), full.toString()))
                    // 客户端断开（Tomcat 报 AsyncRequestNotUsableException/Connection reset）：
                    // 框架层 ERROR 堆栈由 ClientAbortLogFilter 降噪，此处统一记可观测 warn 单行
                    .doOnCancel(() -> log.warn("[stream] 客户端断开，取消推送与编排：sid={}", ctx.sid()))
                    .onErrorResume(ex -> {
                        String err = safeMessage(ex);
                        return Flux.concat(
                                Flux.just("event: " + SseProtocol.EVENT_ERROR + "\ndata: " + err),
                                metaEvent(ctx.sid(), ctx.newSession(), GoalStatus.FAILED.name(), err));
                    });
            return body;
        });
    }

    /**
     * 把 Agent 流出的一行转成 SSE 行序列：
     * - 进度行 {@code \u0000stage\u0001detail} → {@code event: progress} + {@code data: {"stage":..,"detail":..}}
     * - 其它（内容 token）→ {@code data: <token>}
     *
     * <p>progress 的 data JSON 直接用 Jackson 序列化 record，转义交给它，不再手写。
     */
    private static Flux<String> toSseRows(String row) {
        ProgressLine.StageRow p = ProgressLine.decode(row);
        if (p == null) {
            // 内容行：裸换行会把一条 data 断成多个物理行，CLI 只认前缀行会丢内容——必须行内转义（可逆）
            return Flux.just("data: " + SseProtocol.escapeLineBreaks(row));
        }
        try {
            // event 与 data 必须在同一元素内：MVC 逐元素 flush，拆成两个元素会被其它事件的行交叉插入
            return Flux.just("event: " + SseProtocol.EVENT_PROGRESS + "\ndata: " + OBJECT_MAPPER.writeValueAsString(p));
        } catch (Exception e) {
            return Flux.just("event: " + SseProtocol.EVENT_PROGRESS + "\ndata: {\"stage\":\"?\",\"detail\":\"?\"}");
        }
    }

    /** 流式成功后写回会话记忆（响应式路径：assistant 完整回复已由 doOnNext 收集） */
    private void writeBackContext(String sessionId, String message, String assistantReply) {
        if (sessionId != null && !sessionId.isBlank()) {
            sessionService.saveContext(sessionId, new UserMessage(message));
            sessionService.saveContext(sessionId, new AssistantMessage(assistantReply));
            sessionService.touchSession(sessionId, message);
        }
    }

    /** 组装 SSE meta 事件单元素块（event+data 同元素，保证成对不被交叉）：{@code event: meta\n data: {json}} */
    private Flux<String> metaEvent(String sessionId, boolean newSession, String status, String error) {
        SseMeta meta = new SseMeta(sessionId, newSession, null, status, error);
        try {
            return Flux.just("event: " + SseProtocol.EVENT_META + "\ndata: " + OBJECT_MAPPER.writeValueAsString(meta));
        } catch (Exception e) {
            return Flux.just("event: " + SseProtocol.EVENT_META + "\ndata: {\"error\":\"meta serialization failed\"}");
        }
    }

    /** 安全取异常信息，避免 getMessage 为空导致行文本不规范；换行替换为空格避免破坏逐行解析 */
    private static String safeMessage(Throwable ex) {
        String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return msg.replaceAll("[\\r\\n]+", " ");
    }

    /**
     * 主 Agent 前置判断：调用 {@link RouteJudge} 决定走哪条路径。
     * 复杂(COMPLEX) → multi-agent 多 Agent 编排；否则(简单/未知) → 默认 general。
     * 判断异常/失败时兜底默认 Agent（宁可简单，不阻塞请求）。
     *
     * @return 选中的 agent 名（"general" 或 "multi-agent"）
     */
    private String resolveAgent(String message) {
        try {
            RouteDecision route = routeJudge.judge(message);
            log.info("[route] message '{}' -> {} -> agent={}", trimForLog(message), route,
                    route == RouteDecision.COMPLEX ? AgentConstants.MULTI_AGENT : AgentConstants.DEFAULT_AGENT);
            return route == RouteDecision.COMPLEX
                    ? AgentConstants.MULTI_AGENT
                    : AgentConstants.DEFAULT_AGENT;
        } catch (Exception e) {
            // 判断异常不得影响请求主流程：兜底默认（简单）路径
            log.warn("[route] 主 Agent 判断异常，回退默认 agent：{}", safeMessage(e));
            return AgentConstants.DEFAULT_AGENT;
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