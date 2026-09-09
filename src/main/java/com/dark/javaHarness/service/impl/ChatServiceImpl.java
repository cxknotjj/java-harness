package com.dark.javaHarness.service.impl;

import com.dark.javaHarness.agent.ProgressLine;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.domain.RouteDecision;
import com.dark.javaHarness.domain.dto.ChatRequest;
import com.dark.javaHarness.domain.dto.ChatResponse;
import com.dark.javaHarness.domain.dto.KnowledgeSource;
import com.dark.javaHarness.domain.dto.SseMeta;
import com.dark.javaHarness.enums.AgentConstants;
import com.dark.javaHarness.enums.GoalStatus;
import com.dark.javaHarness.enums.SseProtocol;
import com.dark.javaHarness.domain.entity.SessionEntity;
import com.dark.javaHarness.exception.ResumeConflictException;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.ChatService;
import com.dark.javaHarness.service.GoalService;
import com.dark.javaHarness.service.RouteJudge;
import com.dark.javaHarness.service.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
    private final GoalService goalService;
    /** 知识检索器（RAG 出处透出源）；null 时 meta.sources 恒空（知识库禁用场景，Demo 规模 pragmatic 方案） */
    private final com.dark.javaHarness.knowledge.KnowledgeRetriever knowledgeRetriever;

    public ChatServiceImpl(AgentService agentService, SessionService sessionService,
                           RouteJudge routeJudge, GoalService goalService) {
        this(agentService, sessionService, routeJudge, goalService, null);
    }

    /** Spring 装配入口（多构造需显式标注）：knowledgeRetriever 仅知识库启用时非 null */
    @org.springframework.beans.factory.annotation.Autowired
    public ChatServiceImpl(AgentService agentService, SessionService sessionService,
                           RouteJudge routeJudge, GoalService goalService,
                           com.dark.javaHarness.knowledge.KnowledgeRetriever knowledgeRetriever) {
        this.agentService = agentService;
        this.sessionService = sessionService;
        this.routeJudge = routeJudge;
        this.goalService = goalService;
        this.knowledgeRetriever = knowledgeRetriever;
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

        // 主 Agent 前置判断：分流「场景A简单(会话绑定 Agent) / 场景B复杂(multi-agent)」
        String resolvedAgent = resolveAgent(request.message(), sessionId);

        Goal goal = agentService.executeSync(resolvedAgent, request.message(), sessionId);
        writeBackContext(sessionId, request.message(), goal);

        if (goal.status() == GoalStatus.FAILED) {
            return ChatResponse.failure(sessionId, newSession, goal.id(), goal.summary());
        }
        return ChatResponse.success(sessionId, newSession, goal.id(), goal.summary(),
                recentKnowledgeSources(sessionId));
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
     * - 逐 token 产出 {@code event: token} + {@code data: <token>}，结束后产出 [DONE]，末尾产出 meta 事件
     * - agent 流出错时产出 error 事件 + 错误信息，并以 meta(FAILED) 收尾，避免调用方悬挂
     */
    @Override
    public Flux<String> streamReactive(ChatRequest request) {
        String existing = request.sessionId();
        boolean needNew = existing == null || existing.isBlank();
        Mono<SessionCtx> sessionMono = needNew
                ? Mono.fromCallable(() -> sessionService.createSession("anonymous", request.message()))
                        .map(sid -> new SessionCtx(sid, true))
                        .subscribeOn(Schedulers.boundedElastic())
                : Mono.just(new SessionCtx(existing, false));

        return sessionMono.flatMapMany(ctx -> {
            // 请求携带 agentId 即视为「会话内切换 Agent」：同步更新 session 表 agent_id，
            // 会话档案与实际路由保持一致（新建会话则从默认 1 修正为请求指定的 Agent）。
            // 失败只告警不中断：路由侧 executeStreamReactiveByAgentId 对非法 agentId 已回退默认 Agent
            if (request.agentId() != null) {
                try {
                    sessionService.switchAgent(ctx.sid(), request.agentId());
                } catch (Exception e) {
                    log.warn("[chat] 会话 Agent 同步失败（不影响本次路由）sid={} agentId={}: {}",
                            ctx.sid(), request.agentId(), safeMessage(e));
                }
                // 显式指定 agentId 时跳过路由判断：分流结果在该分支用不上，而判断本身是一次
                // 同步 LLM 调用（思考型模型失控时曾阻塞请求 14 分钟，见 llm_call_log #198），
                // 指定 Agent 的请求不必陪跑这段延时与风险
                return toSseBody(withAgentProgress(agentService.findAgentNameById(request.agentId())
                                .orElse(AgentConstants.DEFAULT_AGENT),
                        agentService.executeStreamReactiveByAgentId(request.agentId(), request.message(), ctx.sid())),
                        ctx.sid(), ctx.newSession(), request.message(), null);
            }
            // 主 Agent 前置判断：分流「场景A简单(会话绑定 Agent) / 场景B复杂(multi-agent)」
            String resolvedAgent = resolveAgent(request.message(), ctx.sid());
            return toSseBody(withAgentProgress(resolvedAgent,
                    agentService.executeStreamReactive(resolvedAgent, request.message(), ctx.sid())),
                    ctx.sid(), ctx.newSession(), request.message(), null);
        });
    }

    /** 复杂编排断点续跑：校验 goal 状态后走 multi-agent 检查点续跑（SSE 输出同 streamReactive）。 */
    @Override
    public Flux<String> resume(String goalId) {
        if (goalId == null || goalId.isBlank()) {
            throw new IllegalArgumentException("goalId 不能为空");
        }
        Goal goal = goalService.get(goalId)
                .orElseThrow(() -> new IllegalArgumentException("目标不存在: " + goalId));
        if (goal.status() == GoalStatus.RUNNING) {
            throw new ResumeConflictException("该目标仍在执行中，无法续跑: " + goalId);
        }
        if (goal.status() == GoalStatus.SUCCEEDED) {
            // 已成功的 goal 再 resume 会把状态拉回 RUNNING（kill 进程后卡死），且检查点回放
            // 只是跳过所有节点重复吐最终结果——直接拒绝，避免误以为有新工作发生
            throw new ResumeConflictException("该任务已完成，无需续跑: " + goalId);
        }
        log.info("[resume] goal '{}' 续跑请求（原状态={}）", goal.id(), goal.status());
        return toSseBody(withAgentProgress(AgentConstants.MULTI_AGENT, agentService.resumeStreamReactive(goal)),
                goal.sessionId(), false, goal.objective(), goal.id());
    }

    /**
     * 流首插入 agent 归属进度行（stage=agent, detail=agentName）：CLI 在首个回答 token 前
     * 渲染「agentName&gt; 」前缀，与用户侧「你&gt; 」提示符对称——智能分流下实际路由的 Agent
     * 只有服务端知道。进度行走旁路协议，不计入会话摘要与 goal.summary。
     */
    private static Flux<String> withAgentProgress(String agentName, Flux<String> agentTokens) {
        return Flux.concat(Flux.just(ProgressLine.encode("agent", agentName)), agentTokens);
    }

    /**
     * SSE 包装公共体（stream 与 resume 共用）：进度/内容行转 SSE 事件 + [DONE] + meta 收尾，
     * 成功后写回会话记忆（user=本次消息 objective，assistant=完整回复），出错发 error 事件。
     *
     * @param goalId     meta 事件携带的目标 ID（resume 场景传 goal.id()；全新 stream 时 null）
     */
    private Flux<String> toSseBody(Flux<String> agentTokens, String sessionId, boolean newSession,
                                   String userMessage, String goalId) {
        // doOnNext 收集完整回复，流正常结束后由 doOnComplete 统一写回会话记忆（保持多轮记忆语义）
        // 其中「进度行」（以 ProgressLine.MARK 开头，多 Agent 编排的阶段反馈）不计入会话摘要
        StringBuilder full = new StringBuilder();
        return agentTokens
                .doOnNext(row -> { if (!ProgressLine.isProgress(row)) { full.append(row); } })
                .flatMap(ChatServiceImpl::toSseRows)
                .concatWithValues("event: " + SseProtocol.EVENT_TOKEN
                        + "\ndata: " + SseProtocol.DONE_MARKER)
                .concatWith(metaEvent(sessionId, newSession, goalId, GoalStatus.SUCCEEDED.name(), null))
                .doOnComplete(() -> writeBackContext(sessionId, userMessage, full.toString()))
                // 客户端断开（Tomcat 报 AsyncRequestNotUsableException/Connection reset）：
                // 框架层 ERROR 堆栈由 ClientAbortLogFilter 降噪，此处统一记可观测 warn 单行
                .doOnCancel(() -> log.warn("[stream] 客户端断开，取消推送与编排：sid={}", sessionId))
                .onErrorResume(ex -> {
                    String err = safeMessage(ex);
                    return Flux.concat(
                            Flux.just("event: " + SseProtocol.EVENT_ERROR + "\ndata: " + err),
                            metaEvent(sessionId, newSession, goalId, GoalStatus.FAILED.name(), err));
                });
    }

    /**
     * 把 Agent 流出的一行转成 SSE 行序列：
     * - 进度行 {@code \u0000stage\u0001detail} → {@code event: progress} + {@code data: {"stage":..,"detail":..}}
     * - 其它（内容 token）→ {@code event: token} + {@code data: <token>}
     *
     * <p>progress 的 data JSON 直接用 Jackson 序列化 record，转义交给它，不再手写。
     */
    private static Flux<String> toSseRows(String row) {
        ProgressLine.StageRow p = ProgressLine.decode(row);
        if (p == null) {
            // 内容行：裸换行会把一条 data 断成多个物理行，CLI 只认前缀行会丢内容——必须行内转义（可逆）。
            // event: token 必须显式声明：SSE 的 event 字段粘滞，progress 块之后不带 event: 的
            // data 行会被客户端误归入 progress（token 被吞、CLI 显示 0 字）。
            return Flux.just("event: " + SseProtocol.EVENT_TOKEN
                    + "\ndata: " + SseProtocol.escapeLineBreaks(row));
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
    private Flux<String> metaEvent(String sessionId, boolean newSession, String goalId, String status, String error) {
        SseMeta meta = new SseMeta(sessionId, newSession, goalId, status, error, recentKnowledgeSources(sessionId));
        try {
            return Flux.just("event: " + SseProtocol.EVENT_META + "\ndata: " + OBJECT_MAPPER.writeValueAsString(meta));
        } catch (Exception e) {
            return Flux.just("event: " + SseProtocol.EVENT_META + "\ndata: {\"error\":\"meta serialization failed\"}");
        }
    }

    /** 本次会话最近一次知识命中的出处（RAG 出处透出；禁用/无命中返回 null，旧客户端兼容） */
    private List<KnowledgeSource> recentKnowledgeSources(String sessionId) {
        if (knowledgeRetriever == null) {
            return null;
        }
        List<KnowledgeSource> sources = knowledgeRetriever.recentSources(sessionId);
        return sources.isEmpty() ? null : sources;
    }

    /** 安全取异常信息，避免 getMessage 为空导致行文本不规范；换行替换为空格避免破坏逐行解析 */
    private static String safeMessage(Throwable ex) {
        String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return msg.replaceAll("[\\r\\n]+", " ");
    }

    /**
     * 主 Agent 前置判断：调用 {@link RouteJudge} 决定走哪条路径。
     * 复杂(COMPLEX) → multi-agent 多 Agent 编排；简单/未知 → 会话绑定的 Agent
     * （session.agent_id，如曾用 /agent 切换；未绑定或失效回退默认 general），
     * 不再一律压回 general——会话切过 Agent 后简单问题也应由该 Agent 回答。
     * 判断异常/失败时兜底简单路径（宁可简单，不阻塞请求）。
     *
     * @return 选中的 agent 名（会话 Agent/general 或 "multi-agent"）
     */
    private String resolveAgent(String message, String sessionId) {
        try {
            RouteDecision route = routeJudge.judge(message);
            String resolved = route == RouteDecision.COMPLEX
                    ? AgentConstants.MULTI_AGENT
                    : sessionAgentName(sessionId);
            log.info("[route] message '{}' -> {} -> agent={}", trimForLog(message), route, resolved);
            return resolved;
        } catch (Exception e) {
            // 判断异常不得影响请求主流程：兜底默认（简单）路径
            log.warn("[route] 主 Agent 判断异常，回退会话 Agent：{}", safeMessage(e));
            return sessionAgentName(sessionId);
        }
    }

    /**
     * 解析会话绑定的 Agent 名（session.agent_id → agent 表 agent_name）。
     * 会话不存在/未绑定/查询失败/agent 行已删时回退默认 general——与
     * {@code executeStreamReactiveByAgentId} 未命中回退的语义一致。
     */
    private String sessionAgentName(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return AgentConstants.DEFAULT_AGENT;
        }
        try {
            SessionEntity session = sessionService.getSession(sessionId);
            if (session == null || session.getAgentId() == null) {
                return AgentConstants.DEFAULT_AGENT;
            }
            return agentService.findAgentNameById(session.getAgentId().longValue())
                    .orElse(AgentConstants.DEFAULT_AGENT);
        } catch (Exception e) {
            log.warn("[route] 会话 Agent 解析失败，回退默认 sid={}: {}", sessionId, safeMessage(e));
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