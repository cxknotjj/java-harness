package com.dark.javaHarness.agent;

import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.domain.LlmCallLog;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.impl.LlmCallRecorder;
import com.dark.javaHarness.tool.ToolAssignments;
import com.dark.javaHarness.tool.ToolCallTracer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.ai.chat.client.ChatClient;
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
 * 消耗——阻塞调用取响应 usage 真实值，流式调用无 usage 回包、按输出文本近似估算。
 * 观测失败不影响调用本身。
 */
final class AgentChatCaller {

    /** 默认系统提示词（agent 表无对应行或 prompt 为空时的兜底） */
    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是一个执行任务的通用 AI 助手，请直接给出简洁、可执行的完成结果。";

    private final ChatClientRegistry clientRegistry;
    private final AgentService agentService;
    /** 专家工具分配表：按 agent 名注入请求级工具 */
    private final ToolAssignments toolAssignments;
    /** LLM 调用观测记录器（可 null：无观测场景下直通） */
    private final LlmCallRecorder recorder;

    AgentChatCaller(ChatClientRegistry clientRegistry,
                    AgentService agentService,
                    ToolAssignments toolAssignments) {
        this(clientRegistry, agentService, toolAssignments, null);
    }

    AgentChatCaller(ChatClientRegistry clientRegistry,
                    AgentService agentService,
                    ToolAssignments toolAssignments,
                    LlmCallRecorder recorder) {
        this.clientRegistry = clientRegistry;
        this.agentService = agentService;
        this.toolAssignments = toolAssignments;
        this.recorder = recorder;
    }

    /** 带会话观测的单次调用（推荐入口：sessionId 用于 llm_call_log 归因） */
    String call(String sessionId, String forAgent, String fallbackSystem, String user) {
        return call(sessionId, forAgent, fallbackSystem, user, null);
    }

    /** 带工具事件发射器的调用：emitter 非 null 时本次调用的工具执行起止经其发进度行（供 CLI 展示） */
    String call(String sessionId, String forAgent, String fallbackSystem, String user,
                Consumer<String> toolEmitter) {
        long start = System.currentTimeMillis();
        AgentConfig config = configOf(forAgent);
        String model = config != null ? config.model() : null;
        try {
            org.springframework.ai.chat.model.ChatResponse resp = buildSpec(config, forAgent, fallbackSystem, user, toolEmitter)
                    .call().chatResponse();
            String content = resp == null || resp.getResult() == null
                    || resp.getResult().getOutput() == null
                    ? null : resp.getResult().getOutput().getText();
            Usage usage = resp == null || resp.getMetadata() == null
                    ? null : resp.getMetadata().getUsage();
            recordOk(sessionId, forAgent, model, false, usage, start, content);
            return content;
        } catch (RuntimeException e) {
            recordError(sessionId, forAgent, model, false, start, e);
            throw e;
        }
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
        return stream(sessionId, forAgent, fallbackSystem, user, onToken, null);
    }

    /** 带工具事件发射器的流式调用：语义同 {@link #stream(String, String, String, Consumer)} */
    String stream(String sessionId, String forAgent, String fallbackSystem, String user,
                  Consumer<String> onToken, Consumer<String> toolEmitter) {
        long start = System.currentTimeMillis();
        AgentConfig config = configOf(forAgent);
        String model = config != null ? config.model() : null;
        StringBuilder collected = new StringBuilder();
        try {
            buildSpec(config, forAgent, fallbackSystem, user, toolEmitter)
                    .stream()
                    .content()
                    .doOnNext(token -> {
                        collected.append(token);
                        onToken.accept(token);
                    })
                    .blockLast();
        } catch (RuntimeException e) {
            recordError(sessionId, forAgent, model, true, start, e);
            throw e;
        }
        // 流式无 usage 回包：按已收输出文本近似估算（与 ContextAssemblingAdvisor 同口径）
        String out = collected.toString();
        int tokens = LlmCallRecorder.estimateTokens(out);
        record(sessionId, forAgent, model, true, true, null, tokens, tokens, start, null);
        return out;
    }

    /** 组装请求（查表配置 → 取客户端 → system/user → 请求级 model → 工具注入），call/stream 共用；config 由调用方查好传入（避免重复查表） */
    private ChatClient.ChatClientRequestSpec buildSpec(AgentConfig config, String forAgent, String fallbackSystem,
                                                       String user, Consumer<String> toolEmitter) {
        String model = config != null ? config.model() : null;
        ChatClient client = clientRegistry.get(model);
        boolean hasTablePrompt = config != null && config.prompt() != null && !config.prompt().isBlank();
        String sysText = hasTablePrompt ? config.prompt() : DEFAULT_SYSTEM_PROMPT;
        String userText = hasTablePrompt ? user : fallbackSystem + "\n" + user;
        ChatClient.ChatClientRequestSpec spec = client.prompt()
                .system(sysText)
                .user(userText);
        if (model != null && !model.isBlank()) {
            // Registry 构建的客户端 defaultOptions 为空，必须在请求级显式指定 model，否则厂商端 400
            spec.options(OpenAiChatOptions.builder().model(model).build());
        }
        // 专家工具分配：按 agent 名注入请求级工具（与客户端 defaultTools 合并）
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
                spec.toolCallbacks(traced.toArray(new ToolCallback[0]));
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
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        record(sessionId, agentName, model, stream, false, null, null, null, start, msg);
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
