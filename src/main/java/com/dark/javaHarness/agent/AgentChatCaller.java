package com.dark.javaHarness.agent;

import com.dark.javaHarness.advisor.ContextAssemblingAdvisor;
import com.dark.javaHarness.config.ContextBudgetProperties;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.domain.LlmCallLog;
import com.dark.javaHarness.exception.ModelQuotaException;
import com.dark.javaHarness.prompt.MemoryPolicy;
import com.dark.javaHarness.prompt.PromptAssembler;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.service.impl.LlmCallRecorder;
import com.dark.javaHarness.tool.ToolAssignments;
import com.dark.javaHarness.tool.ToolCallBudget;
import com.dark.javaHarness.tool.ToolCallTracer;
import com.dark.javaHarness.prompt.ToolLazyManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

/**
 * 编排环节的 LLM 单次调用器：查 agent 表配置 → 取注册客户端 → 组装请求 → 调用。
 *
 * <p>供 {@link MultiAgentGraphAgent} 各环节（lead 拆解 / 专家子任务 / 聚合）复用；
 * 每次调用按传入的 agent 名独立查表，同一编排内不同环节可各用各的模型与提示词。
 *
 * <p>提示词经 {@link PromptAssembler} 按段组装：角色段优先级为 agent 表该角色行的
 * prompt &gt; 调用方传入的兜底角色指令 &gt; 内置默认系统提示词；工具索引/工具纪律/
 * 输出约定等段随后按固定次序追加。兜底角色指令不再拼进 user（原双段拼接由
 * 组装管线收敛为 system 段）。
 *
 * <p>记忆注入（spec 子项 5）：经 {@link MemoryPolicy} 按策略挂载会话记忆 advisor——
 * 仅 lead 拆解节点注入（与路径 A 同口径：同一 SessionService 记忆源 +
 * ContextAssemblingAdvisor 预算裁剪），aggregator 与子任务专家不注入；无会话 ID 或
 * 未提供记忆源（单测场景）时跳过。
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

    /**
     * 流式调用空闲超时：相邻信号间隔超过该时长即判定端点挂起，超时失败（不可重试——
     * 实测厂商端对该类请求为稳定挂死，重试同请求只会成倍放大等待）。
     * 取值依据：工具执行期是流上最长的正常静默（fetchUrl/browser 实测 ~8s），
     * 120s 已有 10 倍余量；300s 旧值曾让挂死请求阻塞用户 5 分钟才失败。
     */
    private static final java.time.Duration STREAM_IDLE_TIMEOUT = java.time.Duration.ofSeconds(120);

    /** 取消异常消息（llm_call_log.error_msg 检索用）：客户端断连中止在途请求 */
    private static final String CANCELLED_MSG = "client-cancelled: 客户端断连，中止在途请求";

    /** 取消异常工厂（包级共用：编排节点捕获后需重新抛出同语义异常） */
    static CancellationException cancelException() {
        return new CancellationException(CANCELLED_MSG);
    }

    private final ChatClientRegistry clientRegistry;
    private final AgentService agentService;
    /** 专家工具分配表：按 agent 名注入请求级工具 */
    private final ToolAssignments toolAssignments;
    /** Prompt 组装器：system prompt 按段组装（角色段兜底经 fallbackSystem 传入） */
    private final PromptAssembler promptAssembler;
    /** 记忆注入策略：按角色名判定是否挂载会话记忆 advisor（仅 lead） */
    private final MemoryPolicy memoryPolicy = new MemoryPolicy();
    /** 会话记忆源（SessionService，与路径 A GeneralAssistantAgent 同源）；null 时不注入（单测场景） */
    private final SessionService memoryStore;
    /** 工具 Schema 延迟加载管理器（开关关闭时 process 全量透传，行为与现状一致）；编排三节点共享同一会话展开集 */
    private final ToolLazyManager lazyTools;
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
        this(clientRegistry, agentService, toolAssignments, recorder, retry, budgets,
                new PromptAssembler(agentService, toolAssignments));
    }

    AgentChatCaller(ChatClientRegistry clientRegistry,
                    AgentService agentService,
                    ToolAssignments toolAssignments,
                    LlmCallRecorder recorder,
                    LlmRetry retry,
                    ContextBudgetProperties budgets,
                    PromptAssembler promptAssembler) {
        this(clientRegistry, agentService, toolAssignments, recorder, retry, budgets, promptAssembler, null);
    }

    /**
     * @param memoryStore 会话记忆源（SessionService，与路径 A GeneralAssistantAgent 同源同口径）；
     *                    lead 节点据此注入会话记忆，null 时不注入（单测场景）
     */
    AgentChatCaller(ChatClientRegistry clientRegistry,
                    AgentService agentService,
                    ToolAssignments toolAssignments,
                    LlmCallRecorder recorder,
                    LlmRetry retry,
                    ContextBudgetProperties budgets,
                    PromptAssembler promptAssembler,
                    SessionService memoryStore) {
        this(clientRegistry, agentService, toolAssignments, recorder, retry, budgets,
                promptAssembler, memoryStore, null);
    }

    /**
     * 全参构造：lazyTools 为 null 时构造禁用态实例（旧构造链/单测场景，工具面全量注入现状）。
     * 注意 promptAssembler 的延迟加载标志应与 lazyTools.isEnabled() 同源一致
     * （由 MultiAgentGraphAgent 全参构造统一构建传入）。
     */
    AgentChatCaller(ChatClientRegistry clientRegistry,
                    AgentService agentService,
                    ToolAssignments toolAssignments,
                    LlmCallRecorder recorder,
                    LlmRetry retry,
                    ContextBudgetProperties budgets,
                    PromptAssembler promptAssembler,
                    SessionService memoryStore,
                    ToolLazyManager lazyTools) {
        this.clientRegistry = clientRegistry;
        this.agentService = agentService;
        this.toolAssignments = toolAssignments;
        this.promptAssembler = promptAssembler;
        this.memoryStore = memoryStore;
        this.lazyTools = lazyTools != null ? lazyTools : new ToolLazyManager(toolAssignments, false);
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
                String content = streamAttempt(config, sessionId, forAgent, fallbackSystem, user,
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
                        String content = streamAttempt(config, sessionId, forAgent, fallbackSystem, user,
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
        String content = streamAttempt(config, sessionId, forAgent, fallbackSystem, user,
                toolEmitter, disableTools, extraAdvisors, null, null);
        recordOkEstimated(sessionId, forAgent, model, start, content);
        return content;
    }

    /**
     * 模型幻觉出不存在的工具调用（工具名不在回调列表中，Spring AI 执行阶段抛出）。
     *
     * <p>轻量态兼容（延迟加载开启时）：所有已分配工具名均已注册（轻量 callback），已注册但
     * 未展开的工具被直接调用时走 {@link ToolLazyManager} 的引导文本（正常工具结果，不抛异常），
     * 只有真·未注册名才触发本降级——两分支不冲突，本逻辑保留原样。
     */
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
    private String streamAttempt(AgentConfig config, String sessionId, String forAgent, String fallbackSystem,
                                 String user, Consumer<String> toolEmitter, boolean disableTools,
                                 Advisor[] extraAdvisors, Consumer<String> onToken, BooleanSupplier cancelled) {
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw cancelException();
        }
        StringBuilder collected = new StringBuilder();
        try {
            buildSpec(config, sessionId, forAgent, fallbackSystem, user, toolEmitter, disableTools, extraAdvisors)
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
                buildSpec(config, sessionId, forAgent, fallbackSystem, user, toolEmitter, false, extraAdvisors)
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

    /** 组装请求（查表配置 → 取客户端 → system 经 PromptAssembler 按段组装 → 记忆策略挂载 → 请求级 model → 工具注入），call/stream 共用；config 由调用方查好传入（避免重复查表） */
    private ChatClient.ChatClientRequestSpec buildSpec(AgentConfig config, String sessionId, String forAgent,
                                                       String fallbackSystem, String user,
                                                       Consumer<String> toolEmitter,
                                                       boolean disableTools, Advisor... extraAdvisors) {
        // Registry 模式：凭部署模型 id 取对应厂商的 ChatClient（未绑定/未命中回退默认 DashScope）
        Long modelProviderId = config != null ? config.modelProviderId() : null;
        String model = config != null ? config.model() : null;
        ChatClient client = clientRegistry.get(modelProviderId);
        // system 经 PromptAssembler 按段组装：角色段（表 prompt > 兜底指令 > 默认）+ 索引/纪律/约定等段；
        // 兜底角色指令收敛为角色段兜底，不再拼进 user
        ChatClient.ChatClientRequestSpec spec = client.prompt()
                .system(promptAssembler.assemble(forAgent, fallbackSystem))
                .user(user);
        // 记忆注入（编排路径唯一注入点：lead 拆解）：策略判定 + 会话 ID + 记忆源三重把关，
        // 装配与路径 A 完全同口径（MessageChatMemoryAdvisor + CONVERSATION_ID + ContextAssemblingAdvisor 预算裁剪）；
        // aggregator/子任务专家不挂任何记忆 advisor（子任务上下文由 lead 在子任务描述中传递）
        if (memoryStore != null && memoryPolicy.shouldInject(forAgent, sessionId)) {
            spec.advisors(MessageChatMemoryAdvisor.builder(memoryStore).build());
            spec.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId));
            spec.advisors(new ContextAssemblingAdvisor(budgets.getHistoryBudget()));
        }
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
        // disableTools=true 跳过（幻觉工具调用的降级重试路径）。
        // 双通道统一为 ToolCallback 单通道（@Tool 注解对象经 ToolCallbacks.from 转回调，与
        // .tools 注入等价），便于 tracer/预算/延迟加载统一装饰
        if (disableTools) {
            return spec;
        }
        ToolAssignments.ToolSet toolSet = toolAssignments == null
                ? ToolAssignments.ToolSet.EMPTY
                : toolAssignments.forAgent(forAgent);
        List<ToolCallback> tools = new ArrayList<>(toolSet.callbacks());
        if (!toolSet.annotated().isEmpty()) {
            tools.addAll(List.of(ToolCallbacks.from(toolSet.annotated().toArray())));
        }
        if (toolEmitter != null) {
            // 追踪模式：tracer 装饰真实工具（执行起止经 emitter 发进度行，CLI 工具调用行）
            tools = ToolCallTracer.trace(tools, toolEmitter);
            if (!tools.isEmpty()) {
                // 硬预算：单次调用内工具执行次数超限不再真执行；工具结果总量 ≤5k token，
                // 超出的截断、耗尽后返回引导文本收束循环（防止 token 按轮数平方级膨胀）。
                // 预算只约束真实工具执行——轻量引导与 expand_tool 元工具不计入
                tools = ToolCallBudget.limit(tools,
                        budgets.getToolCallLimit(), budgets.getToolResultBudget());
            }
        }
        // 延迟加载加工（最外层，包 tracer/预算装饰后的 callback）：未展开→轻量包装、
        // 已展开→透传完整 schema，末尾追加 expand_tool 元工具（不经 tracer/预算——
        // 元工具不产生工具行噪声、不占真实执行额度）；开关关闭/无会话 ID 时全量透传现状
        tools = lazyTools.process(sessionId, tools);
        if (!tools.isEmpty()) {
            spec.toolCallbacks(tools.toArray(new ToolCallback[0]));
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
