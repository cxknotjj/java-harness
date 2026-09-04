package com.dark.javaHarness.agent;

import com.dark.javaHarness.config.ContextBudgetProperties;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.domain.LlmCallLog;
import com.dark.javaHarness.exception.ModelQuotaException;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.impl.LlmCallRecorder;
import com.dark.javaHarness.tool.ToolAssignments;
import com.dark.javaHarness.tool.ToolCallBudget;
import com.dark.javaHarness.tool.ToolCallTracer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * 编排环节的 LLM 单次调用器：查 agent 表配置 → 取注册客户端 → 组装请求 → 调用。
 *
 * <p>供 {@link MultiAgentGraphAgent} 各环节（lead 拆解 / 专家子任务 / 聚合）复用；
 * 每次调用按传入的 agent 名独立查表，同一编排内不同环节可各用各的模型与提示词。
 *
 * <p>提示词优先级：agent 表该角色行的 prompt &gt; 调用方传入的兜底角色指令
 * &gt; 内置默认系统提示词。表配置存在时以其为 system、user 不再重复拼接角色指令；
 * 无表配置时回退内置默认 system 并拼接兜底指令。
 *
 * <p>观测：每次调用结束（成功/失败）经 {@link LlmCallRecorder} 异步记录耗时与 token
 * 消耗——call/stream 统一走流式通道后无 usage 回包，token 按输出文本近似估算
 * （tokensEstimated=true）。观测失败不影响调用本身。
 *
 * <p>取消（客户端断连防 token 浪费）：call/stream 均接受可空 {@link BooleanSupplier}
 * 取消令牌——置位后在下一个 token 边界中止在途请求（取消向上传播关闭 HTTP 连接，
 * 厂商端停止生成），抛 {@link CancellationException}；不重试、部分输出不按成功返回。
 */
final class AgentChatCaller {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AgentChatCaller.class);

    /** 流式调用空闲超时：相邻 token 间隔超过该时长即判定端点挂起，超时失败进入调用方回退/重试逻辑 */
    private static final java.time.Duration STREAM_IDLE_TIMEOUT = java.time.Duration.ofSeconds(300);

    /** 取消异常消息（llm_call_log.error_msg 检索用）：客户端断连中止在途请求 */
    private static final String CANCELLED_MSG = "client-cancelled: 客户端断连，中止在途请求";

    /** 取消异常工厂（包级共用：编排节点捕获后需重新抛出同语义异常） */
    static CancellationException cancelException() {
        return new CancellationException(CANCELLED_MSG);
    }

    /** 默认系统提示词（agent 表无对应行或 prompt 为空时的兜底） */
    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是一个执行任务的通用 AI 助手，请直接给出简洁、可执行的完成结果。";

    private final ChatClientRegistry clientRegistry;
    private final AgentService agentService;
    /** 专家工具分配表：按 agent 名注入请求级工具 */
    private final ToolAssignments toolAssignments;
    /** LLM 调用观测记录器（可 null：无观测场景下直通） */
    private final LlmCallRecorder recorder;
    /** 模型调用重试策略（指数退避，最多 3 次） */
    private final LlmRetry retry;
    /** 上下文预算配置（工具次数/结果预算等；null 时用内置默认值，单测场景） */
    private final ContextBudgetProperties budgets;

    AgentChatCaller(ChatClientRegistry clientRegistry,
                    AgentService agentService,
                    ToolAssignments toolAssignments,
                    LlmCallRecorder recorder) {
        this(clientRegistry, agentService, toolAssignments, recorder, new LlmRetry());
    }

    AgentChatCaller(ChatClientRegistry clientRegistry,
                    AgentService agentService,
                    ToolAssignments toolAssignments,
                    LlmCallRecorder recorder,
                    LlmRetry retry) {
        this(clientRegistry, agentService, toolAssignments, recorder, retry, null);
    }

    AgentChatCaller(ChatClientRegistry clientRegistry,
                    AgentService agentService,
                    ToolAssignments toolAssignments,
                    LlmCallRecorder recorder,
                    ContextBudgetProperties budgets) {
        this(clientRegistry, agentService, toolAssignments, recorder, new LlmRetry(), budgets);
    }

    AgentChatCaller(ChatClientRegistry clientRegistry,
                    AgentService agentService,
                    ToolAssignments toolAssignments,
                    LlmCallRecorder recorder,
                    LlmRetry retry,
                    ContextBudgetProperties budgets) {
        this.clientRegistry = clientRegistry;
        this.agentService = agentService;
        this.toolAssignments = toolAssignments;
        this.recorder = recorder;
        this.retry = retry;
        this.budgets = budgets != null ? budgets : new ContextBudgetProperties();
    }

    /** 带会话观测的单次调用（推荐入口：sessionId 用于 llm_call_log 归因） */
    String call(String sessionId, String forAgent, String fallbackSystem, String user) {
        return call(sessionId, forAgent, fallbackSystem, user, null, new Advisor[0], null);
    }

    /**
     * 带请求级 advisor 挂载的单次调用（如 lead/聚合的 PromptBudgetAdvisor）：
     * toolEmitter 可为 null（无工具进度行）；extraAdvisors 为请求级 advisor（可变参数，可为空）。
     */
    String call(String sessionId, String forAgent, String fallbackSystem, String user,
                Consumer<String> toolEmitter, Advisor... extraAdvisors) {
        return call(sessionId, forAgent, fallbackSystem, user, toolEmitter, extraAdvisors, null);
    }

    /**
     * 带取消令牌的单次调用（编排节点传入共享断连标志）。
     *
     * <p>实现说明：底层统一走流式通道收集完整内容返回——RestClient 阻塞调用不可中断
     * （JDK HttpClient 不响应线程中断），流式是 Spring AI 1.1.4 + JDK 连接器下唯一
     * 能中止在途 HTTP 请求的通道；代价是 token 用量从响应 usage 真实值变为估算。
     *
     * <p>取消语义：cancelled 已置位时直接抛 {@link CancellationException}（零 HTTP 请求）；
     * 执行中置位时在下一个 token 边界中止并抛出——不重试、部分输出不按成功返回。
     */
    String call(String sessionId, String forAgent, String fallbackSystem, String user,
                Consumer<String> toolEmitter, Advisor[] extraAdvisors, BooleanSupplier cancelled) {
        AgentConfig config = configOf(forAgent);
        String model = config != null ? config.model() : null;
        // 模型调用失败自动重试（最多 3 次、指数退避）；单次调用含观测埋点
        return retry.executeWithRetry(() -> {
            long start = System.currentTimeMillis();
            try {
                String content = streamAttempt(config, forAgent, fallbackSystem, user,
                        toolEmitter, false, extraAdvisors, null, cancelled);
                recordOkEstimated(sessionId, forAgent, model, start, content);
                return content;
            } catch (RuntimeException e) {
                // 客户端断连中止：记录后立即上抛（CancellationException 不可重试，直接放行）
                if (e instanceof CancellationException) {
                    recordError(sessionId, forAgent, model, true, start, e);
                    throw e;
                }
                // 账户级硬错误（余额不足/配额耗尽）：重试无意义，立即转人话异常向上传播
                if (ModelQuotaException.matches(e)) {
                    recordError(sessionId, forAgent, model, true, start, e);
                    throw ModelQuotaException.from(e, model);
                }
                // 模型可能把提示词里的专家名（researcher 等）误当工具发起调用——
                // 工具列表里没有该名字，Spring AI 执行时抛「No ToolCallback found」。
                // 此时去掉工具列表重试一次：模型纯文本作答仍可产出结果，不炸整个编排。
                if (isUnknownToolCall(e)) {
                    log.warn("[caller] {} 发起未知名工具调用，去工具重试一次：{}", forAgent, safeMsg(e));
                    long start2 = System.currentTimeMillis();
                    try {
                        String content = streamAttempt(config, forAgent, fallbackSystem, user,
                                null, true, extraAdvisors, null, cancelled);
                        recordOkEstimated(sessionId, forAgent, model, start2, content);
                        return content;
                    } catch (RuntimeException e2) {
                        recordError(sessionId, forAgent, model, true, start2, e2);
                        throw e2;
                    }
                }
                recordError(sessionId, forAgent, model, true, start, e);
                throw e;
            }
        });
    }

    /** 单次调用 + 成功观测记录（失败由调用方记录）；disableTools=true 时不注入任何工具（幻觉工具调用的降级路径） */
    String invokeAndRecord(AgentConfig config, String sessionId, String forAgent,
                           String fallbackSystem, String user, Consumer<String> toolEmitter,
                           boolean disableTools, String model, long start, Advisor... extraAdvisors) {
        String content = streamAttempt(config, forAgent, fallbackSystem, user,
                toolEmitter, disableTools, extraAdvisors, null, null);
        recordOkEstimated(sessionId, forAgent, model, start, content);
        return content;
    }

    /** 模型幻觉出不存在的工具调用（工具名不在回调列表中，Spring AI 执行阶段抛出） */
    private static boolean isUnknownToolCall(RuntimeException e) {
        String msg = e.getMessage();
        return msg != null && msg.contains("No ToolCallback found for tool name");
    }

    /**
     * 单次流式调用尝试（不做重试——重试由 call 的 {@link LlmRetry} / stream 的循环自行处理）：
     * 收集全部 token 阻塞至流结束，返回完整内容；onToken 可 null（无需实时回调）。
     *
     * <p>取消语义：cancelled 已置位时直接抛取消异常（零 HTTP 请求）；执行中置位时
     * takeUntil 在下一个 token 边界中止订阅——取消向上传播关闭 HTTP 连接（厂商端
     * 停止生成），部分输出不返回。
     */
    private String streamAttempt(AgentConfig config, String forAgent, String fallbackSystem, String user,
                                 Consumer<String> toolEmitter, boolean disableTools, Advisor[] extraAdvisors,
                                 Consumer<String> onToken, BooleanSupplier cancelled) {
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw cancelException();
        }
        StringBuilder collected = new StringBuilder();
        try {
            buildSpec(config, forAgent, fallbackSystem, user, toolEmitter, disableTools, extraAdvisors)
                    .stream()
                    .content()
                    // 端点无响应兜底：JDK 连接器无读超时，流空闲超时由此处兜住（防永久挂起）
                    .timeout(STREAM_IDLE_TIMEOUT)
                    .takeUntil(__ -> cancelled != null && cancelled.getAsBoolean())
                    .doOnNext(token -> {
                        if (cancelled != null && cancelled.getAsBoolean()) {
                            // takeUntil 放行的终止前元素在此拦截；异常致流以错误终止，
                            // Reactor cancel 向上游传播关闭 HTTP 连接
                            throw cancelException();
                        }
                        collected.append(token);
                        if (onToken != null) {
                            onToken.accept(token);
                        }
                    })
                    .blockLast();
        } catch (RuntimeException e) {
            // 取消置位时一律按取消归因（流取消竞态下 blockLast 可能抛出其他形态异常）
            if (cancelled != null && cancelled.getAsBoolean()) {
                throw cancelException();
            }
            throw e;
        }
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw cancelException();
        }
        return collected.toString();
    }

    /** 模型空响应防御：逐层取 assistant 文本，任一层缺失返回 null（call/stream 记录与展示共用） */
    static String contentOf(org.springframework.ai.chat.model.ChatResponse resp) {
        return resp != null && resp.getResult() != null && resp.getResult().getOutput() != null
                ? resp.getResult().getOutput().getText() : null;
    }

    /** 模型空响应防御：逐层取 usage，任一层缺失返回 null */
    static Usage usageOf(org.springframework.ai.chat.model.ChatResponse resp) {
        return resp != null && resp.getMetadata() != null ? resp.getMetadata().getUsage() : null;
    }

    private static String safeMsg(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * 流式 ChatClient 调用：请求组装与 {@link #call} 完全一致，但走 stream 通道——
     * 每个 token 到达即回调 {@code onToken}，方法阻塞至流结束并返回完整内容。
     *
     * <p>供图节点（如聚合）在生成过程中实时向外推送 token；调用方负责异常处理
     * （本方法不做降级，流式失败直接抛出，由调用方回退阻塞调用）。
     *
     * @param onToken 每个 token 片段到达时的回调（可能包含空串）
     */
    String stream(String sessionId, String forAgent, String fallbackSystem, String user,
                  Consumer<String> onToken) {
        return stream(sessionId, forAgent, fallbackSystem, user, onToken, null, new Advisor[0]);
    }

    /**
     * 带请求级 advisor 挂载的流式调用（如聚合的 PromptBudgetAdvisor）：
     * toolEmitter 可为 null（无工具进度行）；extraAdvisors 为请求级 advisor（可变参数，可为空）。
     */
    String stream(String sessionId, String forAgent, String fallbackSystem, String user,
                  Consumer<String> onToken, Consumer<String> toolEmitter, Advisor... extraAdvisors) {
        return stream(sessionId, forAgent, fallbackSystem, user, onToken, toolEmitter, extraAdvisors, null);
    }

    /**
     * 带取消令牌的流式调用（编排节点传入共享断连标志）：取消已置位时立即抛取消异常
     * （零 HTTP 请求）；执行中置位时在下一个 token 边界中止（takeUntil 取消向上传播
     * 关闭 HTTP 连接），抛取消异常——不重试、部分输出不按成功返回。
     */
    String stream(String sessionId, String forAgent, String fallbackSystem, String user,
                  Consumer<String> onToken, Consumer<String> toolEmitter, Advisor[] extraAdvisors,
                  BooleanSupplier cancelled) {
        if (cancelled != null && cancelled.getAsBoolean()) {
            recordError(sessionId, forAgent, null, true, System.currentTimeMillis(),
                    cancelException());
            throw cancelException();
        }
        AgentConfig config = configOf(forAgent);
        String model = config != null ? config.model() : null;
        // 流式重试约束：仅「首个 token 尚未发出」的失败才允许重试（一旦开始输出，
        // onToken 已回调、无法回滚，重试会造成重复输出）；已产生输出则立即抛出。
        // 取消异常永不重试（取消不是可重试错误，是断连语义）。
        for (int attempt = 1; attempt <= retry.maxAttempts(); attempt++) {
            long start = System.currentTimeMillis();
            StringBuilder collected = new StringBuilder();
            try {
                buildSpec(config, forAgent, fallbackSystem, user, toolEmitter, false, extraAdvisors)
                        .stream()
                        .content()
                        // 端点无响应兜底：JDK 连接器无读超时，流空闲超时由此处兜住（防永久挂起）
                        .timeout(STREAM_IDLE_TIMEOUT)
                        .takeUntil(__ -> cancelled != null && cancelled.getAsBoolean())
                        .doOnNext(token -> {
                            if (cancelled != null && cancelled.getAsBoolean()) {
                                // takeUntil 放行的终止前元素在此拦截，取消向上传播关连接
                                throw cancelException();
                            }
                            collected.append(token);
                            onToken.accept(token);
                        })
                        .blockLast();
            } catch (RuntimeException e) {
                boolean isCancel = e instanceof CancellationException
                        || (cancelled != null && cancelled.getAsBoolean());
                if (isCancel) {
                    recordError(sessionId, forAgent, model, true, start,
                            cancelException());
                    throw cancelException();
                }
                recordError(sessionId, forAgent, model, true, start, e);
                // 账户级硬错误：与阻塞（call）路径同口径转换，不重试直接抛人话异常
                if (ModelQuotaException.matches(e)) {
                    throw ModelQuotaException.from(e, model);
                }
                boolean partialOutput = collected.length() > 0;
                boolean canRetry = !partialOutput && LlmRetry.isRetryable(e) && attempt < retry.maxAttempts();
                if (canRetry) {
                    retry.waitBeforeRetry(attempt);
                    continue;
                }
                throw e;
            }
            if (cancelled != null && cancelled.getAsBoolean()) {
                // 流正常结束但取消竞态置位：不按成功返回
                recordError(sessionId, forAgent, model, true, start,
                        cancelException());
                throw cancelException();
            }
            // 流式无 usage 回包：按已收输出文本近似估算（与 ContextAssemblingAdvisor 同口径）
            String out = collected.toString();
            int tokens = LlmCallRecorder.estimateTokens(out);
            record(sessionId, forAgent, model, true, true, null, tokens, tokens, start, null);
            return out;
        }
        // 理论不可达（maxAttempts>=1）
        throw new IllegalStateException("stream 重试循环异常退出");
    }

    /** 组装请求（查表配置 → 取客户端 → system/user → 请求级 model → 工具注入），call/stream 共用；config 由调用方查好传入（避免重复查表） */
    private ChatClient.ChatClientRequestSpec buildSpec(AgentConfig config, String forAgent, String fallbackSystem,
                                                       String user, Consumer<String> toolEmitter,
                                                       boolean disableTools, Advisor... extraAdvisors) {
        // Registry 模式：凭部署模型 id 取对应厂商的 ChatClient（未绑定/未命中回退默认 DashScope）
        Long modelProviderId = config != null ? config.modelProviderId() : null;
        String model = config != null ? config.model() : null;
        ChatClient client = clientRegistry.get(modelProviderId);
        boolean hasTablePrompt = config != null && config.prompt() != null && !config.prompt().isBlank();
        String sysText = hasTablePrompt ? config.prompt() : DEFAULT_SYSTEM_PROMPT;
        String userText = hasTablePrompt ? user : fallbackSystem + "\n" + user;
        ChatClient.ChatClientRequestSpec spec = client.prompt()
                .system(sysText)
                .user(userText);
        // 请求级 advisor 挂载（如 lead/聚合的 PromptBudgetAdvisor）：
        // 不用 default advisor——聚合与子任务共用同一客户端，default 挂载会连坐到无关调用
        for (Advisor advisor : extraAdvisors) {
            spec.advisors(advisor);
        }
        if (model != null && !model.isBlank()) {
            // Registry 构建的客户端 defaultOptions 为空，必须在请求级显式指定 model，否则厂商端 400
            // frequencyPenalty：长报告聚合场景下模型易陷入重复循环（同一段落循环生成多次），
            // 用频率惩罚抑制；对 lead 的 JSON 输出无副作用
            spec.options(OpenAiChatOptions.builder()
                    .model(model)
                    .frequencyPenalty(0.5)
                    .build());
        }
        // 专家工具分配：按 agent 名注入请求级工具（与客户端 defaultTools 合并）；
        // disableTools=true 跳过（幻觉工具调用的降级重试路径）
        if (disableTools) {
            return spec;
        }
        ToolAssignments.ToolSet toolSet = toolAssignments == null
                ? ToolAssignments.ToolSet.EMPTY
                : toolAssignments.forAgent(forAgent);
        if (toolEmitter != null) {
            // 追踪模式：双通道统一为「装饰后的 ToolCallback」单通道——
            // @Tool 注解对象也转回调再装饰，工具执行起止经 emitter 发进度行（CLI 工具调用行）
            List<ToolCallback> traced = new ArrayList<>(
                    ToolCallTracer.trace(toolSet.callbacks(), toolEmitter));
            traced.addAll(ToolCallTracer.traceAnnotated(toolSet.annotated(), toolEmitter));
            if (!traced.isEmpty()) {
                // 硬预算：单次调用内工具执行次数超限不再真执行；工具结果总量 ≤5k token，
                // 超出的截断、耗尽后返回引导文本收束循环（防止 token 按轮数平方级膨胀）
                spec.toolCallbacks(ToolCallBudget.limit(traced,
                                budgets.getToolCallLimit(), budgets.getToolResultBudget())
                        .toArray(new ToolCallback[0]));
            }
            return spec;
        }
        if (!toolSet.annotated().isEmpty()) {
            // 必须 toArray 走 varargs Object... 重载（@Tool 对象解析）；传 List 会匹配
            // List<ToolCallback> 重载导致「No @Tool annotated methods found」异常
            spec.tools(toolSet.annotated().toArray());
        }
        if (!toolSet.callbacks().isEmpty()) {
            spec.toolCallbacks(toolSet.callbacks().toArray(new ToolCallback[0]));
        }
        return spec;
    }

    /** 查 agent 表配置（每次 LLM 调用仅查一次，观测记录与请求组装共用） */
    private AgentConfig configOf(String forAgent) {
        return agentService == null ? null
                : agentService.getAgentConfig(forAgent).orElse(null);
    }

    /** 成功记录（流式估算口径）：call 统一走流式通道无 usage 回包，按输出文本近似估算 token */
    private void recordOkEstimated(String sessionId, String agentName, String model, long start, String content) {
        int tokens = LlmCallRecorder.estimateTokens(content);
        record(sessionId, agentName, model, true, true, null, tokens, tokens, start, null);
    }

    /** 成功记录：usage 可解析则记真实 token，否则留空 */
    private void recordOk(String sessionId, String agentName, String model, boolean stream,
                          Usage usage, long start, String content) {
        Integer prompt = usage == null ? null : usage.getPromptTokens();
        Integer completion = usage == null ? null : usage.getCompletionTokens();
        Integer total = usage == null ? null : usage.getTotalTokens();
        record(sessionId, agentName, model, stream, true,
                prompt, completion, total, start, null);
    }

    private void recordError(String sessionId, String agentName, String model, boolean stream,
                             long start, Exception e) {
        record(sessionId, agentName, model, stream, false, null, null, null, start, safeMsg(e));
    }

    private void record(String sessionId, String agentName, String model, boolean stream, boolean ok,
                        Integer promptTokens, Integer completionTokens, Integer totalTokens,
                        long start, String errorMsg) {
        if (recorder == null) {
            return;
        }
        recorder.record(new LlmCallLog(sessionId, agentName, model, stream, ok,
                promptTokens, completionTokens, totalTokens,
                /* tokensEstimated */ stream && completionTokens == null,
                System.currentTimeMillis() - start, errorMsg));
    }
}
