package com.dark.javaHarness.agent;

import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.tool.ToolAssignments;
import org.springframework.ai.chat.client.ChatClient;
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
 */
final class AgentChatCaller {

    /** 默认系统提示词（agent 表无对应行或 prompt 为空时的兜底） */
    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是一个执行任务的通用 AI 助手，请直接给出简洁、可执行的完成结果。";

    private final ChatClientRegistry clientRegistry;
    private final AgentService agentService;
    /** 专家工具分配表：按 agent 名注入请求级工具 */
    private final ToolAssignments toolAssignments;

    AgentChatCaller(ChatClientRegistry clientRegistry,
                    AgentService agentService,
                    ToolAssignments toolAssignments) {
        this.clientRegistry = clientRegistry;
        this.agentService = agentService;
        this.toolAssignments = toolAssignments;
    }

    /**
     * 单次 ChatClient 调用：按 agent 名查 agent 表运行配置（model/prompt），再从注册表取客户端。
     *
     * @param forAgent       用于查配置的 agent 名（角色/专家名）
     * @param fallbackSystem 表无配置时拼接进 user 的兜底角色指令
     * @param user           任务指令（纯任务内容，不含角色设定）
     */
    String call(String forAgent, String fallbackSystem, String user) {
        AgentConfig config = agentService == null ? null
                : agentService.getAgentConfig(forAgent).orElse(null);
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
        if (!toolSet.annotated().isEmpty()) {
            // 必须 toArray 走 varargs Object... 重载（@Tool 对象解析）；传 List 会匹配
            // List<ToolCallback> 重载导致「No @Tool annotated methods found」异常
            spec.tools(toolSet.annotated().toArray());
        }
        if (!toolSet.callbacks().isEmpty()) {
            spec.toolCallbacks(toolSet.callbacks().toArray(new ToolCallback[0]));
        }
        return spec.call().content();
    }
}
