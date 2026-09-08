package com.dark.javaHarness.agent;

import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.domain.LlmCallLog;
import com.dark.javaHarness.prompt.PromptAssembler;
import com.dark.javaHarness.prompt.SkillManager;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.service.impl.LlmCallRecorder;
import com.dark.javaHarness.tool.ToolAssignments;
import com.dark.javaHarness.prompt.ToolLazyManager;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.SignalType;

/**
 * 通用 ChatModel 驱动 Agent：使用 Spring AI ChatClient 调用大模型来完成目标。
 *
 * 模型与系统提示词通过 AgentService.getAgentConfig() 从 agent 表读取
 * （按本实例的 agentName 匹配）：每次调用使用表中 model / prompt 组装请求，
 * 并按 model 字段从 ChatClientRegistry 取对应厂商的 ChatClient（Registry 模式）
 *
 * 多实例（多 Agent）复用同一类：通过构造传入的 agentName 组装出
 * 不同“名称 → 模型/服务商”的 Agent，新增 Agent 只需注册一个 bean + agent 表一行，
 * 无需改动 ChatService。新增厂商只需在 ChatClientRegistry 中 register 即可。
 *
 * 多轮会话记忆基于 session + session_messages 两张表（一对一）。
 * 已注册工具调用：模型可按需调用 DemoTools（取时间/计算）与 WebTools（网页抓取）。
 */
public class GeneralAssistantAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(GeneralAssistantAgent.class);

    private static final String DEFAULT_SYSTEM_PROMPT = "你是一个执行任务的 AI 助手，请直接给出简洁、可执行的完成结果。你能记住本会话之前的对话内容，回答时结合历史上下文。";

    private final String agentName;
    private final ChatClientRegistry clientRegistry;
    private final SessionService memoryStore;
    private final AgentService agentService;
    /** 工具分配表：general 注入全量工具（deepseek 等其他实例未登记则仅默认工具） */
    private final ToolAssignments toolAssignments;
    /** Prompt 组装器：system prompt 按段组装（角色段沿用 agent 表 prompt 优先级） */
    private final PromptAssembler promptAssembler;
    /** 工具 Schema 延迟加载管理器（开关关闭时 process 全量透传，行为与现状一致） */
    private final ToolLazyManager lazyTools;
    /** skill 装配管理器（load_skill 元工具来源；null=不注册，单测/旧构造链场景） */
    private final SkillManager skillManager;
    /** LLM 调用观测记录器（可 null：无观测场景下直通） */
    private final LlmCallRecorder recorder;
    /** 模型调用重试策略（最多 3 次、指数退避） */
    private final LlmRetry retry;
    /** 请求规格组装工厂：system/记忆/选项/工具注入的统一组装链（与路径 B 编排共用） */
    private final AgentRequestSpecFactory specFactory;
    /** 输出封顶（final 档，与编排聚合同为直出用户的最终回答；0 = 不限制），来自 app.context.max-tokens-final */
    private final int maxTokensFinal;

    public GeneralAssistantAgent(String agentName,
                                 ChatClientRegistry clientRegistry,
                                 SessionService memoryStore,
                                 AgentService agentService,
                                 ToolAssignments toolAssignments,
                                 LlmCallRecorder recorder) {
        this(agentName, clientRegistry, memoryStore, agentService, toolAssignments, recorder, null);
    }

    /** budgets：上下文预算配置（路径 A 会话历史裁剪预算；null 时用内置默认值，单测场景） */
    public GeneralAssistantAgent(String agentName,
                                 ChatClientRegistry clientRegistry,
                                 SessionService memoryStore,
                                 AgentService agentService,
                                 ToolAssignments toolAssignments,
                                 LlmCallRecorder recorder,
                                 com.dark.javaHarness.config.ContextBudgetProperties budgets) {
        this(agentName, clientRegistry, memoryStore, agentService, toolAssignments, recorder, budgets, null);
    }

    /**
     * 全参构造：lazyTools 为 null 时构造禁用态实例（旧构造链/单测场景，工具面全量注入现状）。
     * 正式装配由 ChatAgentConfig 注入共享实例（app.prompt.lazy-tools.enabled 开关）。
     */
    public GeneralAssistantAgent(String agentName,
                                 ChatClientRegistry clientRegistry,
                                 SessionService memoryStore,
                                 AgentService agentService,
                                 ToolAssignments toolAssignments,
                                 LlmCallRecorder recorder,
                                 com.dark.javaHarness.config.ContextBudgetProperties budgets,
                                 ToolLazyManager lazyTools) {
        this(agentName, clientRegistry, memoryStore, agentService, toolAssignments,
                recorder, budgets, lazyTools, null, null);
    }

    /**
     * 全参构造（含 skill 装配）：promptAssembler/skillManager 为 null 时内部裸构建
     * （旧构造链/单测场景，无 skill 段、不注册 load_skill）；正式装配由 ChatAgentConfig/
     * AgentRegistry 注入共享实例（skill 段提供者与 toolLazyManager 开关对齐）。
     */
    public GeneralAssistantAgent(String agentName,
                                 ChatClientRegistry clientRegistry,
                                 SessionService memoryStore,
                                 AgentService agentService,
                                 ToolAssignments toolAssignments,
                                 LlmCallRecorder recorder,
                                 com.dark.javaHarness.config.ContextBudgetProperties budgets,
                                 ToolLazyManager lazyTools,
                                 PromptAssembler promptAssembler,
                                 SkillManager skillManager) {
        this.agentName = agentName;
        this.clientRegistry = clientRegistry;
        this.memoryStore = memoryStore;
        this.agentService = agentService;
        this.toolAssignments = toolAssignments;
        this.lazyTools = lazyTools != null ? lazyTools : new ToolLazyManager(toolAssignments, false);
        // 工具索引段与延迟加载同源：开启时索引段追加 expand_tool 使用引导（与轻量态工具面对齐）
        this.promptAssembler = promptAssembler != null ? promptAssembler
                : new PromptAssembler(agentService, toolAssignments, List.of(), this.lazyTools.isEnabled());
        this.skillManager = skillManager;
        this.recorder = recorder;
        // budgets 缺省时取配置类默认（与 application.yaml 生产默认一致的单一数值源），
        // 不在此处硬编码兜底数字，避免代码/yaml 双口径漂移
        com.dark.javaHarness.config.ContextBudgetProperties effectiveBudgets =
                budgets != null ? budgets : new com.dark.javaHarness.config.ContextBudgetProperties();
        this.maxTokensFinal = effectiveBudgets.getMaxTokensFinal();
        this.retry = new LlmRetry();
        this.specFactory = new AgentRequestSpecFactory(clientRegistry, this.promptAssembler,
                toolAssignments, this.lazyTools, skillManager, memoryStore, effectiveBudgets);
    }

    /** 返回 Agent 名称（用于注册与路由） */
    @Override
    public String name() {
        return agentName;
    }

    /** 同步执行目标：调用大模型返回完整回复（历史只读注入，写入由 ChatService 统一负责） */
    @Override
    public String execute(Goal goal) {
        log.info("AI agent '{}' 开始处理目标: {}", name(), goal.objective());
        // 模型调用失败自动重试（最多 3 次、指数退避）；单次调用含观测埋点
        return retry.executeWithRetry(() -> {
            long start = System.currentTimeMillis();
            try {
                org.springframework.ai.chat.model.ChatResponse resp =
                        buildChatRequestSpec(goal.sessionId(), goal.objective()).call().chatResponse();
                String reply = AgentChatCaller.contentOf(resp);
                recordCall(goal.sessionId(), false, true,
                        AgentChatCaller.usageOf(resp), null, start, null);
                log.info("AI agent '{}' 得到回复: {}", name(), reply);
                // 会话记忆写回不在此处做，统一由 ChatService 负责（与流式路径保持一致）
                return reply;
            } catch (RuntimeException e) {
                recordCall(goal.sessionId(), false, false, null, null, start, e);
                throw e;
            }
        });
    }

    /**
     * 流式执行：用 ChatClient.stream() 逐 token 回调 onToken（真正边收边发，
     * 非先收集后返回）。数据流在内部由 reactor 驱动，完整结果由调用方在
     * onToken 回调中拼接（如 AgentService 收集后写入 Goal.summary）。
     * 注意：记忆持久化不在此处做，交给 ChatService 在流结束后统一写回。
     */
    @Override
    public void executeStream(Goal goal, Consumer<String> onToken) {
        log.info("AI agent '{}' 开始流式处理目标: {}", name(), goal.objective());
        long start = System.currentTimeMillis();
        StringBuilder collected = new StringBuilder();
        // streamUsage 开启后末帧回传真实 usage（无则维持估算兜底）
        java.util.concurrent.atomic.AtomicReference<org.springframework.ai.chat.metadata.Usage> usageRef =
                new java.util.concurrent.atomic.AtomicReference<>();

        Flux<String> flux = buildChatRequestSpec(goal.sessionId(), goal.objective())
                .stream()
                .chatResponse()
                .doOnNext(resp -> captureUsage(resp, usageRef))
                // streamUsage 末帧是只含 usage 的空帧（contentOf 为 null）：Reactor 的 map
                // 不允许 null 返回（直接抛「The mapper returned a null value」），后面的
                // filter 根本不会执行——必须用 handle 跳过空帧
                .handle((org.springframework.ai.chat.model.ChatResponse resp,
                         reactor.core.publisher.SynchronousSink<String> sink) -> {
                    String token = AgentChatCaller.contentOf(resp);
                    if (token != null) {
                        sink.next(token);
                    }
                });
        // 真正逐 token 推送：订阅流，每来一个 token 立即回调，阻塞等待流结束
        try {
            flux.doOnNext(token -> {
                        if (onToken != null) {
                            onToken.accept(token);
                        }
                    })
                    .doOnNext(collected::append)
                    .then()
                    .block();
        } catch (RuntimeException e) {
            recordCall(goal.sessionId(), true, false, usageRef.get(), collected, start, e);
            throw e;
        }
        recordCall(goal.sessionId(), true, true, usageRef.get(), collected, start, null);
        log.info("AI agent '{}' 流式输出完成", name());
    }

    /**
     * 响应式流式执行：真·逐 token 发射（ChatClient.stream().content()）。
     * 覆写接口 default——否则会退化为「同步 execute 阻塞生成完 → 一次性产出」，CLI 将全程无输出干等。
     * 工具调用起止经旁路 sink 合并进同一 Flux（ProgressLine 进度行，与路径 B 通道一致），
     * CLI 据此展示工具调用行；会话记忆写入统一由 ChatService 在流结束后负责，此处不做。
     */
    @Override
    public Flux<String> executeStreamReactive(Goal goal) {
        log.info("AI agent '{}' 开始响应式流式处理目标: {}", name(), goal.objective());
        Sinks.Many<String> toolEvents = Sinks.many().unicast().onBackpressureBuffer();
        long start = System.currentTimeMillis();
        StringBuilder collected = new StringBuilder();
        // 流式异常的真实原因（供应商 4xx/5xx 报错、读超时等）在 Flux 错误通道里，
        // doFinally 只有信号没有异常体——用 doOnError 抓住真实 Throwable 供观测落库
        java.util.concurrent.atomic.AtomicReference<Throwable> streamError =
                new java.util.concurrent.atomic.AtomicReference<>();
        // streamUsage 开启后末帧回传真实 usage（无则维持估算兜底）
        java.util.concurrent.atomic.AtomicReference<org.springframework.ai.chat.metadata.Usage> usageRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        // 关闸挂 merge 之前的主干段（多 Agent 侧同款死锁教训：关闸在 merge 后会循环等待）
        Flux<String> content = buildChatRequestSpec(goal.sessionId(), goal.objective(),
                        row -> BranchProgressListener.tryEmitSerialized(toolEvents, row))
                .stream()
                .chatResponse()
                .doOnNext(resp -> captureUsage(resp, usageRef))
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
                .doOnNext(collected::append)
                .doOnError(streamError::set)
                .doFinally(sig -> {
                    // 终结（含 cancel/error）时记录本次调用观测：streamUsage 末帧 usage 优先，
                    // 无则按已收文本估算。error 带真实异常（原因链展开后有供应商响应体），
                    // CANCEL 单独标注不再记 null
                    Throwable err;
                    if (sig == SignalType.ON_ERROR) {
                        err = streamError.get() != null
                                ? streamError.get()
                                : new IllegalStateException("流式异常终止");
                    } else if (sig == SignalType.CANCEL) {
                        err = new IllegalStateException("客户端断开（stream cancel）");
                    } else {
                        err = null;
                    }
                    recordCall(goal.sessionId(), true, err == null,
                            usageRef.get(), collected, start, err);
                    BranchProgressListener.tryCompleteSerialized(toolEvents);
                });
        return content.mergeWith(toolEvents.asFlux());
    }

    /** 从流式 chatResponse 捕获 usage（streamUsage 末帧回传真实值；取最后一个非空有效帧） */
    private static void captureUsage(org.springframework.ai.chat.model.ChatResponse resp,
                                     java.util.concurrent.atomic.AtomicReference<org.springframework.ai.chat.metadata.Usage> ref) {
        org.springframework.ai.chat.metadata.Usage usage = AgentChatCaller.usageOf(resp);
        if (usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0) {
            ref.set(usage);
        }
    }

    /**
     * 观测记录：调用结束（成功/失败/cancel）异步落 llm_call_log。
     * 阻塞调用 usage 非 null 时记真实 token；流式（usage=null）按已收输出文本近似估算。
     * 错误描述经 {@link LlmCallRecorder#describeError} 展开原因链（含供应商响应体）。
     */
    private void recordCall(String sessionId, boolean stream, boolean ok,
                            org.springframework.ai.chat.metadata.Usage usage,
                            StringBuilder collected, long start, Throwable error) {
        if (recorder == null) {
            return;
        }
        Integer prompt = usage == null ? null : usage.getPromptTokens();
        Integer completion = usage == null ? null : usage.getCompletionTokens();
        Integer totalVal = usage == null ? null : usage.getTotalTokens();
        Integer completionVal = completion;
        boolean estimated = false;
        if (stream && completionVal == null) {
            // 流式无 usage 回包：估算输出 token（与 ContextAssemblingAdvisor 同口径）
            completionVal = LlmCallRecorder.estimateTokens(collected == null ? "" : collected.toString());
            totalVal = completionVal;
            estimated = true;
        }
        recorder.record(new LlmCallLog(sessionId, agentName,
                agentService.getAgentConfig(agentName).map(AgentConfig::model).orElse(null),
                stream, ok, prompt, completionVal, totalVal, estimated,
                System.currentTimeMillis() - start, LlmCallRecorder.describeError(error)));
    }

    /** 组装一次聊天请求规格：按 agent 表模型取 ChatClient；历史只读注入，上下文由组装拦截器裁剪；system 经 PromptAssembler 按段组装 */
    private ChatClient.ChatClientRequestSpec buildChatRequestSpec(String sessionId, String objective) {
        return buildChatRequestSpec(sessionId, objective, null);
    }

    /**
     * 组装请求（toolEmitter 非 null 时注入追踪版工具，调用起止经其发进度行）：
     * 委托 {@link AgentRequestSpecFactory} 共用组装链（与路径 B AgentChatCaller 合并），
     * 路径 A 差异在此声明——恒注入记忆（ContextAssemblingAdvisor 对「历史 + 本轮」整体
     * 归一化与预算裁剪）、无频率惩罚、无工具次数预算、输出封顶走 final 档。
     */
    private ChatClient.ChatClientRequestSpec buildChatRequestSpec(String sessionId, String objective,
                                                                  Consumer<String> toolEmitter) {
        AgentConfig config = agentService.getAgentConfig(agentName)
                .orElse(new AgentConfig(null, null, null));
        String model = config.model();
        // Registry 模式：凭部署模型 id 取对应厂商的 ChatClient（未绑定/未命中回退默认 DashScope）
        ChatClient client = clientRegistry.get(config.modelProviderId());
        log.info("[agent请求] agentName='{}' -> 配置 modelProviderId={}, model='{}'，实际使用 client={}",
                name(), config.modelProviderId(), model, client == null ? "null" : client.getClass().getSimpleName());
        return specFactory.build(config, sessionId, agentName, DEFAULT_SYSTEM_PROMPT, objective,
                new AgentRequestSpecFactory.Assembly(toolEmitter, false, true, false, false, maxTokensFinal));
    }
}
