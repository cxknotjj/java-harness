package com.dark.javaHarness.agent;

import com.dark.javaHarness.advisor.ContextAssemblingAdvisor;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.domain.LlmCallLog;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.service.impl.LlmCallRecorder;
import com.dark.javaHarness.tool.ToolAssignments;
import com.dark.javaHarness.tool.ToolCallTracer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
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
    /** LLM 调用观测记录器（可 null：无观测场景下直通） */
    private final LlmCallRecorder recorder;
    /** 模型调用重试策略（最多 3 次、指数退避） */
    private final LlmRetry retry;
    /** 会话历史裁剪预算（token），来自 app.context.history-budget 配置 */
    private final int historyBudget;

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
        this.agentName = agentName;
        this.clientRegistry = clientRegistry;
        this.memoryStore = memoryStore;
        this.agentService = agentService;
        this.toolAssignments = toolAssignments;
        this.recorder = recorder;
        this.historyBudget = budgets != null ? budgets.getHistoryBudget() : 4000;
        this.retry = new LlmRetry();
    }

    /** 返回 Agent 名称（用于注册与路由） */
    @Override
    public String name() {
        return agentName;
    }

    /** 同步执行目标：调用大模型返回完整回复（历史由 MessageChatMemoryAdvisor 注入） */
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

        Flux<String> flux = buildChatRequestSpec(goal.sessionId(), goal.objective())
                .stream()
                .content();
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
            recordCall(goal.sessionId(), true, false, null, collected, start, e);
            throw e;
        }
        recordCall(goal.sessionId(), true, true, null, collected, start, null);
        log.info("AI agent '{}' 流式输出完成", name());
    }

    /**
     * 响应式流式执行：真·逐 token 发射（ChatClient.stream().content()）。
     * 覆写接口 default——否则会退化为「同步 execute 阻塞生成完 → 一次性产出」，CLI 将全程无输出干等。
     * 工具调用起止经旁路 sink 合并进同一 Flux（ProgressLine 进度行，与路径 B 通道一致），
     * CLI 据此展示工具调用行；会话记忆持久化由框架 Advisor 与 ChatService 在流结束后统一负责，此处不做。
     */
    @Override
    public Flux<String> executeStreamReactive(Goal goal) {
        log.info("AI agent '{}' 开始响应式流式处理目标: {}", name(), goal.objective());
        Sinks.Many<String> toolEvents = Sinks.many().unicast().onBackpressureBuffer();
        long start = System.currentTimeMillis();
        StringBuilder collected = new StringBuilder();
        // 关闸挂 merge 之前的主干段（多 Agent 侧同款死锁教训：关闸在 merge 后会循环等待）
        Flux<String> content = buildChatRequestSpec(goal.sessionId(), goal.objective(),
                        row -> BranchProgressListener.tryEmitSerialized(toolEvents, row))
                .stream()
                .content()
                .doOnNext(collected::append)
                .doFinally(sig -> {
                    // 终结（含 cancel/error）时记录本次调用观测：流式无 usage，按已收文本估算
                    recordCall(goal.sessionId(), true, sig != SignalType.CANCEL && sig != SignalType.ON_ERROR,
                            null, collected, start,
                            sig == SignalType.ON_ERROR ? new IllegalStateException("流式异常终止") : null);
                    BranchProgressListener.tryCompleteSerialized(toolEvents);
                });
        return content.mergeWith(toolEvents.asFlux());
    }

    /**
     * 观测记录：调用结束（成功/失败/cancel）异步落 llm_call_log。
     * 阻塞调用 usage 非 null 时记真实 token；流式（usage=null）按已收输出文本近似估算。
     */
    private void recordCall(String sessionId, boolean stream, boolean ok,
                            org.springframework.ai.chat.metadata.Usage usage,
                            StringBuilder collected, long start, Exception error) {
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
        String msg = error == null ? null
                : (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        recorder.record(new LlmCallLog(sessionId, agentName,
                agentService.getAgentConfig(agentName).map(AgentConfig::model).orElse(null),
                stream, ok, prompt, completionVal, totalVal, estimated,
                System.currentTimeMillis() - start, msg));
    }

    /** 组装一次聊天请求规格：按 agent 表模型取 ChatClient；历史由 MessageChatMemoryAdvisor 注入，上下文由组装拦截器裁剪 */
    private ChatClient.ChatClientRequestSpec buildChatRequestSpec(String sessionId, String objective) {
        return buildChatRequestSpec(sessionId, objective, null);
    }

    /** 组装请求（toolEmitter 非 null 时注入追踪版工具，调用起止经其发进度行） */
    private ChatClient.ChatClientRequestSpec buildChatRequestSpec(String sessionId, String objective,
                                                                  Consumer<String> toolEmitter) {
        AgentConfig config = agentService.getAgentConfig(agentName)
                .orElse(new AgentConfig(null, null, DEFAULT_SYSTEM_PROMPT));
        String model = config.model();
        // Registry 模式：凭部署模型 id 取对应厂商的 ChatClient（未绑定/未命中回退默认 DashScope）
        ChatClient client = clientRegistry.get(config.modelProviderId());
        log.info("[agent请求] agentName='{}' -> 配置 modelProviderId={}, model='{}'，实际使用 client={}",
                name(), config.modelProviderId(), model, client == null ? "null" : client.getClass().getSimpleName());
        ChatClient.ChatClientRequestSpec spec = client.prompt()
                // 官方记忆 Advisor：从 SessionService(ChatMemory) 按会话ID注入历史
                .advisors(MessageChatMemoryAdvisor.builder(memoryStore).build())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                // 上下义组装拦截器：对注入后的序列做过滤/截断/role 归一化（token 预算控制）
                .advisors(new ContextAssemblingAdvisor(historyBudget))
                .system(config.prompt() != null ? config.prompt() : DEFAULT_SYSTEM_PROMPT)
                .user(objective);
        // 请求级工具注入（本 agent 名分配到的工具集，general=全量；与客户端 defaultTools 合并）
        ToolAssignments.ToolSet toolSet = toolAssignments == null
                ? ToolAssignments.ToolSet.EMPTY
                : toolAssignments.forAgent(agentName);
        if (toolEmitter != null) {
            // 追踪模式：双通道统一为「装饰后的 ToolCallback」单通道，工具执行起止经 emitter 发进度行
            List<ToolCallback> traced = new ArrayList<>(
                    ToolCallTracer.trace(toolSet.callbacks(), toolEmitter));
            traced.addAll(ToolCallTracer.traceAnnotated(toolSet.annotated(), toolEmitter));
            if (!traced.isEmpty()) {
                spec.toolCallbacks(traced.toArray(new ToolCallback[0]));
            }
        } else {
            if (!toolSet.annotated().isEmpty()) {
                // 必须 toArray 走 varargs Object... 重载（@Tool 对象解析）；传 List 会匹配
                // List<ToolCallback> 重载导致「No @Tool annotated methods found」异常
                spec.tools(toolSet.annotated().toArray());
            }
            if (!toolSet.callbacks().isEmpty()) {
                spec.toolCallbacks(toolSet.callbacks().toArray(new ToolCallback[0]));
            }
        }
        if (model != null && !model.isBlank()) {
            spec.options(OpenAiChatOptions.builder().model(model).build());
        }
        return spec;
    }
}
