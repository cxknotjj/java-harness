package com.dark.javaHarness.agent;

import com.dark.javaHarness.config.ContextBudgetProperties;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.domain.LlmCallLog;
import com.dark.javaHarness.exception.ModelQuotaException;
import com.dark.javaHarness.prompt.MemoryPolicy;
import com.dark.javaHarness.prompt.PromptAssembler;
import com.dark.javaHarness.prompt.SkillManager;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.service.impl.LlmCallRecorder;
import com.dark.javaHarness.tool.ToolAssignments;
import com.dark.javaHarness.prompt.ToolLazyManager;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.metadata.Usage;

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
 * <p>记忆注入（spec 子项 5）：经 {@link MemoryPolicy} 按策略只读注入会话历史——仅 lead 拆解
 * 节点注入（手动 loadContext 拼进请求消息 + ContextAssemblingAdvisor 预算裁剪，不挂
 * MessageChatMemoryAdvisor 以避免其自动写回污染会话），aggregator 与子任务专家不注入；
 * 无会话 ID 或未提供记忆源（单测场景）时跳过。
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

    /**
     * 编排预算账本句柄（MultiAgentGraphAgent 按节点注入，同一编排共享同一账本）：
     * <ul>
     *   <li>{@link #overBudget()}：熔断判定（budget>0 且已消耗 ≥ 上限）。调用器在两个时点
     *       检查——发起调用前（零 HTTP 短路）与每次 LLM roundtrip 的 usage 帧到达时（含
     *       单次 call 内部工具循环的每轮，超限即断流，阻止下一轮发起）；
     *   <li>{@link #recordUsage(int, boolean)}：按 roundtrip 增量同步累计消耗（同一调用栈内
     *       可读，不依赖 llm_call_log 异步落库时序）。真实 usage 优先（streamUsage 帧），
     *       全程无 usage 时按输出文本估算并置 estimated=true（口径与 tokens_estimated 一致）。
     * </ul>
     * 聚合等「必发不受熔断」的调用方传 record-only 句柄（overBudget 恒 false，仅记账）。
     */
    interface BudgetLedger {

        /** 熔断判定：true = 已达编排消费上限 */
        boolean overBudget();

        /** 累计消耗（estimated=true 表示含估算值，降级说明注明口径） */
        void recordUsage(int totalTokens, boolean estimated);
    }

    /**
     * 编排预算熔断异常：超限断流/拒绝发起新调用时抛出。编排节点捕获后写
     * 「预算超限跳过」占位并注入聚合降级说明；非可重试错误（LlmRetry 天然旁路）。
     */
    static final class BudgetExceededException extends RuntimeException {

        BudgetExceededException() {
            super("budget-exceeded: 编排 token 消费已达上限，熔断中止调用");
        }
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
    /** skill 装配管理器（load_skill 元工具来源）；null 时不注册元工具（单测/旧构造链场景） */
    private final SkillManager skillManager;
    /** LLM 调用观测记录器（可 null：无观测场景下直通） */
    private final LlmCallRecorder recorder;
    /** 模型调用重试策略（指数退避，最多 3 次） */
    private final LlmRetry retry;
    /** 上下文预算配置（工具次数/结果预算等；null 时用内置默认值，单测场景） */
    private final ContextBudgetProperties budgets;
    /** 请求规格组装工厂：system/记忆/选项/工具注入的统一组装链（与路径 A 共用） */
    private final AgentRequestSpecFactory specFactory;

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
        this(clientRegistry, agentService, toolAssignments, recorder, retry, budgets,
                promptAssembler, memoryStore, lazyTools, null);
    }

    /**
     * 全参构造（含 skill 装配）：skillManager 为 null 时不注册 load_skill 元工具
     * （单测/旧构造链场景）；正式装配由 MultiAgentGraphAgent 透传共享实例。
     * knowledgeRetriever 为 null 时零行为变化（知识库禁用/单测场景）。
     */
    AgentChatCaller(ChatClientRegistry clientRegistry,
                    AgentService agentService,
                    ToolAssignments toolAssignments,
                    LlmCallRecorder recorder,
                    LlmRetry retry,
                    ContextBudgetProperties budgets,
                    PromptAssembler promptAssembler,
                    SessionService memoryStore,
                    ToolLazyManager lazyTools,
                    SkillManager skillManager) {
        this(clientRegistry, agentService, toolAssignments, recorder, retry, budgets,
                promptAssembler, memoryStore, lazyTools, skillManager, null);
    }

    /**
     * 全参构造（含 skill 装配与 RAG 知识检索）：knowledgeRetriever 仅知识库启用时非 null
     * （MultiAgentGraphAgent 经 ObjectProvider 传入），null 时无知识段注入、行为退化现状。
     */
    AgentChatCaller(ChatClientRegistry clientRegistry,
                    AgentService agentService,
                    ToolAssignments toolAssignments,
                    LlmCallRecorder recorder,
                    LlmRetry retry,
                    ContextBudgetProperties budgets,
                    PromptAssembler promptAssembler,
                    SessionService memoryStore,
                    ToolLazyManager lazyTools,
                    SkillManager skillManager,
                    com.dark.javaHarness.knowledge.KnowledgeRetriever knowledgeRetriever) {
        this.clientRegistry = clientRegistry;
        this.agentService = agentService;
        this.toolAssignments = toolAssignments;
        this.promptAssembler = promptAssembler;
        this.memoryStore = memoryStore;
        this.lazyTools = lazyTools != null ? lazyTools : new ToolLazyManager(toolAssignments, false);
        this.skillManager = skillManager;
        this.recorder = recorder;
        this.retry = retry;
        this.budgets = budgets != null ? budgets : new ContextBudgetProperties();
        this.specFactory = new AgentRequestSpecFactory(clientRegistry, promptAssembler,
                toolAssignments, this.lazyTools, skillManager, memoryStore, this.budgets,
                knowledgeRetriever, recorder);
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
        return call(sessionId, forAgent, fallbackSystem, user, toolEmitter, extraAdvisors, cancelled, null);
    }

    /**
     * 带取消令牌与预算账本的单次调用（编排节点传账本句柄：发起前与每轮 roundtrip 的
     * usage 帧上熔断判定；按 roundtrip 增量同步累计消耗）。ledger 可为 null（无账本场景）。
     */
    String call(String sessionId, String forAgent, String fallbackSystem, String user,
                Consumer<String> toolEmitter, Advisor[] extraAdvisors, BooleanSupplier cancelled,
                BudgetLedger ledger) {
        AgentConfig config = configOf(forAgent);
        String model = config != null ? config.model() : null;
        // 模型调用失败自动重试（最多 3 次、指数退避）；单次调用含观测埋点
        return retry.executeWithRetry(() -> {
            long start = System.currentTimeMillis();
            try {
                java.util.concurrent.atomic.AtomicReference<Usage> usageRef =
                        new java.util.concurrent.atomic.AtomicReference<>();
                String content = streamAttempt(config, sessionId, forAgent, fallbackSystem, user,
                        toolEmitter, false, extraAdvisors, null, cancelled, usageRef, ledger);
                recordOkStream(sessionId, forAgent, model, start, content, usageRef.get());
                recordEstimatedIfNoUsage(ledger, content, usageRef.get());
                return content;
            } catch (RuntimeException e) {
                // 客户端断连中止：记录后立即上抛（CancellationException 不可重试，直接放行）
                if (e instanceof CancellationException) {
                    recordError(sessionId, forAgent, model, true, start, e);
                    throw e;
                }
                // 编排预算熔断：政策性中止（非模型错误），记录后立即上抛（不可重试，
                // 编排节点捕获后写「预算超限跳过」占位）
                if (e instanceof BudgetExceededException) {
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
                        java.util.concurrent.atomic.AtomicReference<Usage> usageRef2 =
                                new java.util.concurrent.atomic.AtomicReference<>();
                        String content = streamAttempt(config, sessionId, forAgent, fallbackSystem, user,
                                null, true, extraAdvisors, null, cancelled, usageRef2, ledger);
                        recordOkStream(sessionId, forAgent, model, start2, content, usageRef2.get());
                        recordEstimatedIfNoUsage(ledger, content, usageRef2.get());
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
        java.util.concurrent.atomic.AtomicReference<Usage> usageRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        String content = streamAttempt(config, sessionId, forAgent, fallbackSystem, user,
                toolEmitter, disableTools, extraAdvisors, null, null, usageRef, null);
        recordOkStream(sessionId, forAgent, model, start, content, usageRef.get());
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
     *
     * <p>usageRef 非 null 时捕获 streamUsage 末帧真实 usage，供记录真实 token（null 则纯收集）。
     *
     * <p>预算门控（ledger 非 null）：发起前超限直接抛 {@link BudgetExceededException}
     * （零 HTTP 请求）；每轮 LLM roundtrip 的 usage 帧到达时按增量记账并复检——
     * 单次 call 内部工具循环的下一轮在超限后不再发起（断流阻止后续消耗）。
     */
    private String streamAttempt(AgentConfig config, String sessionId, String forAgent, String fallbackSystem,
                                 String user, Consumer<String> toolEmitter, boolean disableTools,
                                 Advisor[] extraAdvisors, Consumer<String> onToken, BooleanSupplier cancelled,
                                 java.util.concurrent.atomic.AtomicReference<Usage> usageRef,
                                 BudgetLedger ledger) {
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw cancelException();
        }
        if (ledger != null && ledger.overBudget()) {
            throw new BudgetExceededException();
        }
        StringBuilder collected = new StringBuilder();
        // roundtrip 增量记账游标：usage 帧的 total 为该轮完整 prompt+completion，
        // 与上一轮差值即本轮新增消耗（各轮 prompt 单调递增，差值非负、求和=末轮 total，不重复计数）
        java.util.concurrent.atomic.AtomicLong prevTotal = new java.util.concurrent.atomic.AtomicLong();
        try {
            return streamCore(config, sessionId, forAgent, fallbackSystem, user, toolEmitter, disableTools,
                    extraAdvisors, collected, onToken, cancelled, usageRef, prevTotal, ledger);
        } catch (RuntimeException e) {
            // 取消置位时一律按取消归因（流取消竞态下 blockLast 可能抛出其他形态异常）
            if (cancelled != null && cancelled.getAsBoolean()) {
                throw cancelException();
            }
            throw e;
        }
    }

    /**
     * 流式管道共用体（{@link #streamAttempt} 与 {@link #stream} 的重试循环体同构，抽此共用）：
     * 组装请求 → stream → usage 捕获/预算增量记账 → 空帧跳过 → 空闲超时 → 取消拦截 → 收集返回。
     * 取消语义：takeUntil 在下一个 token 边界中止订阅（取消向上传播关闭 HTTP 连接），
     * doOnNext 拦截 takeUntil 放行的终止前元素；流结束后取消竞态复检（不按成功返回）。
     */
    private String streamCore(AgentConfig config, String sessionId, String forAgent, String fallbackSystem,
                              String user, Consumer<String> toolEmitter, boolean disableTools,
                              Advisor[] extraAdvisors, StringBuilder collected, Consumer<String> onToken,
                              BooleanSupplier cancelled,
                              java.util.concurrent.atomic.AtomicReference<Usage> usageRef,
                              java.util.concurrent.atomic.AtomicLong prevTotal, BudgetLedger ledger) {
        buildSpec(config, sessionId, forAgent, fallbackSystem, user, toolEmitter, disableTools, extraAdvisors)
                .stream()
                .chatResponse()
                .doOnNext(resp -> captureUsageAndAccount(resp, usageRef, ledger, prevTotal))
                // streamUsage 末帧是只含 usage 的空帧（contentOf 为 null）：Reactor 的 map
                // 不允许 null 返回（直接抛「The mapper returned a null value」），后面的
                // filter 根本不会执行——必须用 handle 跳过空帧
                .handle((org.springframework.ai.chat.model.ChatResponse resp,
                         reactor.core.publisher.SynchronousSink<String> sink) -> {
                    String token = AgentChatCaller.contentOf(resp);
                    if (token != null) {
                        sink.next(token);
                    }
                })
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

    /**
     * 流帧处理：捕获 usage（llm_call_log 末帧口径不变）+ 预算账本按 roundtrip 增量记账与熔断复检
     * （ledger 为 null 时仅捕获）。超限即抛 {@link BudgetExceededException} 断流——Reactor
     * 取消向上传播关闭 HTTP 连接，工具循环的下一轮不再发起。
     */
    private static void captureUsageAndAccount(org.springframework.ai.chat.model.ChatResponse resp,
                                               java.util.concurrent.atomic.AtomicReference<Usage> ref,
                                               BudgetLedger ledger,
                                               java.util.concurrent.atomic.AtomicLong prevTotal) {
        captureUsage(resp, ref);
        if (ledger == null) {
            return;
        }
        Usage usage = usageOf(resp);
        if (usage == null || usage.getTotalTokens() == null || usage.getTotalTokens() <= 0) {
            return;
        }
        int total = usage.getTotalTokens();
        int delta = (int) Math.max(0, total - prevTotal.getAndSet(total));
        if (delta > 0) {
            ledger.recordUsage(delta, false);
        }
        if (ledger.overBudget()) {
            throw new BudgetExceededException();
        }
    }

    /**
     * 全程无真实 usage 回包时按输出文本估算入账（estimated=true，口径与 llm_call_log.tokens_estimated
     * 一致）；有 usage 时增量已在流帧上记账，此处不再累计（避免重复计数）。ledger 可 null 直通。
     */
    private static void recordEstimatedIfNoUsage(BudgetLedger ledger, String content, Usage usage) {
        if (ledger == null) {
            return;
        }
        boolean hasRealUsage = usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0;
        if (!hasRealUsage) {
            ledger.recordUsage(LlmCallRecorder.estimateTokens(content), true);
        }
    }

    private static String safeMsg(Exception e) {
        // 原因链展开：供应商 4xx/5xx 的响应体（报错 JSON）在 HttpStatusCodeException 里，
        // 外层 wrapper 的 getMessage() 常为空或泛化，直接取会丢真实报错
        return LlmCallRecorder.describeError(e);
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
        return stream(sessionId, forAgent, fallbackSystem, user, onToken, toolEmitter, extraAdvisors,
                cancelled, null);
    }

    /**
     * 带取消令牌与预算账本的流式调用（编排节点传账本句柄：发起前熔断判定 + 成功结束后
     * 估算兜底入账/真实增量入账）。ledger 可为 null（无账本场景）。
     */
    String stream(String sessionId, String forAgent, String fallbackSystem, String user,
                  Consumer<String> onToken, Consumer<String> toolEmitter, Advisor[] extraAdvisors,
                  BooleanSupplier cancelled, BudgetLedger ledger) {
        if (cancelled != null && cancelled.getAsBoolean()) {
            recordError(sessionId, forAgent, null, true, System.currentTimeMillis(),
                    cancelException());
            throw cancelException();
        }
        if (ledger != null && ledger.overBudget()) {
            throw new BudgetExceededException();
        }
        AgentConfig config = configOf(forAgent);
        String model = config != null ? config.model() : null;
        // 流式重试约束：仅「首个 token 尚未发出」的失败才允许重试（一旦开始输出，
        // onToken 已回调、无法回滚，重试会造成重复输出）；已产生输出则立即抛出。
        // 取消异常永不重试（取消不是可重试错误，是断连语义）。
        for (int attempt = 1; attempt <= retry.maxAttempts(); attempt++) {
            long start = System.currentTimeMillis();
            StringBuilder collected = new StringBuilder();
            // streamUsage 末帧真实 usage（无则估算兜底）
            java.util.concurrent.atomic.AtomicReference<Usage> usageRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            // roundtrip 增量记账游标（口径同 streamAttempt，见其注释）
            java.util.concurrent.atomic.AtomicLong prevTotal = new java.util.concurrent.atomic.AtomicLong();
            try {
                String out = streamCore(config, sessionId, forAgent, fallbackSystem, user, toolEmitter, false,
                        extraAdvisors, collected, onToken, cancelled, usageRef, prevTotal, ledger);
                // streamUsage 回传真实 usage 时记真实值，无则按已收输出文本近似估算（原口径兜底）；
                // 账本兜底入账：无真实 usage 帧时按输出估算补记（有则增量已在流帧上记账，不重复）
                recordOkStream(sessionId, forAgent, model, start, out, usageRef.get());
                recordEstimatedIfNoUsage(ledger, out, usageRef.get());
                return out;
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
        }
        // 理论不可达（maxAttempts>=1）
        throw new IllegalStateException("stream 重试循环异常退出");
    }

    /**
     * 组装请求：委托 {@link AgentRequestSpecFactory} 共用组装链（与路径 A GeneralAssistantAgent
     * 合并），编排路径差异在此声明——频率惩罚与工具硬预算启用、记忆按 {@link MemoryPolicy}
     * 判定（仅 lead 注入）、maxTokens 按角色档位；config 由调用方查好传入（避免重复查表）。
     */
    private ChatClient.ChatClientRequestSpec buildSpec(AgentConfig config, String sessionId, String forAgent,
                                                       String fallbackSystem, String user,
                                                       Consumer<String> toolEmitter,
                                                       boolean disableTools, Advisor... extraAdvisors) {
        return specFactory.build(config, sessionId, forAgent, fallbackSystem, user,
                new AgentRequestSpecFactory.Assembly(toolEmitter, disableTools,
                        memoryStore != null && memoryPolicy.shouldInject(forAgent, sessionId),
                        true, true, maxTokensForRole(forAgent)),
                extraAdvisors);
    }

    /** 查 agent 表配置（每次 LLM 调用仅查一次，观测记录与请求组装共用） */
    private AgentConfig configOf(String forAgent) {
        return agentService == null ? null
                : agentService.getAgentConfig(forAgent).orElse(null);
    }

    /**
     * 输出封顶档位映射（消费侧 maxTokens，0 = 不限制）：
     * lead 拆解 → lead 档（JSON 中间产物本就该短）；aggregator → final 档
     * （与路径 A 直出对话同为直出用户的最终回答，共用一档）；其余（编排子任务专家
     * researcher/coder/analyst/writer/general）→ expert 档。
     * 角色名字面量与 {@link MemoryPolicy} / MultiAgentGraphAgent 的编排角色名同源。
     */
    private int maxTokensForRole(String forAgent) {
        if ("lead".equals(forAgent)) {
            return budgets.getMaxTokensLead();
        }
        if ("aggregator".equals(forAgent)) {
            return budgets.getMaxTokensFinal();
        }
        return budgets.getMaxTokensExpert();
    }

    /** 成功记录（流式）：streamUsage 末帧回传真实 usage 时记真实 token，无则按输出文本估算兜底 */
    private void recordOkStream(String sessionId, String agentName, String model, long start,
                                String content, Usage usage) {
        Integer prompt = usage == null ? null : usage.getPromptTokens();
        Integer completion = usage == null ? null : usage.getCompletionTokens();
        Integer total = usage == null ? null : usage.getTotalTokens();
        if (completion == null) {
            int tokens = LlmCallRecorder.estimateTokens(content);
            completion = tokens;
            total = tokens;
        }
        record(sessionId, agentName, model, true, true, prompt, completion, total, start, null);
    }

    /** 模型空响应防御：从流式 chatResponse 捕获 usage（streamUsage 末帧回传真实值；取最后一个非空有效帧） */
    private static void captureUsage(org.springframework.ai.chat.model.ChatResponse resp,
                                     java.util.concurrent.atomic.AtomicReference<Usage> ref) {
        if (ref == null) {
            return;
        }
        Usage usage = usageOf(resp);
        if (usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0) {
            ref.set(usage);
        }
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
