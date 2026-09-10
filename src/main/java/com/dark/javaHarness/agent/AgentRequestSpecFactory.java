package com.dark.javaHarness.agent;

import com.dark.javaHarness.advisor.ContextAssemblingAdvisor;
import com.dark.javaHarness.config.ContextBudgetProperties;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.knowledge.KnowledgeRetriever;
import com.dark.javaHarness.prompt.PromptAssembler;
import com.dark.javaHarness.prompt.SkillManager;
import com.dark.javaHarness.prompt.ToolLazyManager;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.tool.ToolAssignments;
import com.dark.javaHarness.tool.ToolCallBudget;
import com.dark.javaHarness.tool.ToolCallTracer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

/**
 * ChatClient 请求规格组装工厂：路径 A（{@link GeneralAssistantAgent}）与路径 B
 * （{@link AgentChatCaller}）共用的请求组装链——system 按段组装、会话记忆只读注入、
 * 请求级选项（model / streamUsage / maxTokens 档位）与工具注入装饰
 * （tracer → 工具预算 → 延迟加载 → load_skill）。
 *
 * <p>两条路径的差异点经 {@link Assembly} 显式声明（频率惩罚与工具次数预算仅编排路径
 * 启用、记忆注入条件由调用方策略判定、maxTokens 档位由调用方解析），工厂本身不感知
 * 编排角色语义。
 */
final class AgentRequestSpecFactory {

    /**
     * 单次请求的组装差异项（两条路径各自声明）：
     * <ul>
     *   <li>{@code toolEmitter}：非 null 时注入追踪版工具（执行起止经其发进度行）</li>
     *   <li>{@code disableTools}：true 跳过全部工具注入（幻觉工具调用的降级重试路径）</li>
     *   <li>{@code injectMemory}：是否只读注入会话历史（编排路径按 MemoryPolicy 判定仅 lead；
     *       路径 A 恒注入）</li>
     *   <li>{@code frequencyPenalty}：频率惩罚（长报告聚合复读抑制，仅编排路径启用）</li>
     *   <li>{@code toolCallBudget}：工具次数/结果硬预算（仅编排路径启用）</li>
     *   <li>{@code maxTokens}：输出封顶档位（0 = 不限制，不写入保持模型默认）</li>
     * </ul>
     */
    record Assembly(Consumer<String> toolEmitter,
                    boolean disableTools,
                    boolean injectMemory,
                    boolean frequencyPenalty,
                    boolean toolCallBudget,
                    int maxTokens) {
    }

    private final ChatClientRegistry clientRegistry;
    private final ToolAssignments toolAssignments;
    private final PromptAssembler promptAssembler;
    private final ToolLazyManager lazyTools;
    private final SkillManager skillManager;
    private final SessionService memoryStore;
    private final ContextBudgetProperties budgets;
    /** 知识检索器（RAG 注入面）；null 时零行为变化（知识库禁用/单测场景，沿用 skillManager==null 约定） */
    private final KnowledgeRetriever knowledgeRetriever;

    AgentRequestSpecFactory(ChatClientRegistry clientRegistry,
                            PromptAssembler promptAssembler,
                            ToolAssignments toolAssignments,
                            ToolLazyManager lazyTools,
                            SkillManager skillManager,
                            SessionService memoryStore,
                            ContextBudgetProperties budgets) {
        this(clientRegistry, promptAssembler, toolAssignments, lazyTools, skillManager,
                memoryStore, budgets, null);
    }

    AgentRequestSpecFactory(ChatClientRegistry clientRegistry,
                            PromptAssembler promptAssembler,
                            ToolAssignments toolAssignments,
                            ToolLazyManager lazyTools,
                            SkillManager skillManager,
                            SessionService memoryStore,
                            ContextBudgetProperties budgets,
                            KnowledgeRetriever knowledgeRetriever) {
        this.clientRegistry = clientRegistry;
        this.promptAssembler = promptAssembler;
        this.toolAssignments = toolAssignments;
        this.lazyTools = lazyTools;
        this.skillManager = skillManager;
        this.memoryStore = memoryStore;
        this.budgets = budgets;
        this.knowledgeRetriever = knowledgeRetriever;
    }

    /** 组装请求（取客户端 → system 按段组装 → 记忆只读注入 → 请求级 advisor → 选项 → 工具注入） */
    ChatClient.ChatClientRequestSpec build(AgentConfig config, String sessionId, String forAgent,
                                           String fallbackSystem, String user,
                                           Assembly assembly, Advisor... extraAdvisors) {
        // Registry 模式：凭部署模型 id 取对应厂商的 ChatClient（未绑定/未命中回退默认 DashScope）
        Long modelProviderId = config != null ? config.modelProviderId() : null;
        String model = config != null ? config.model() : null;
        ChatClient client = clientRegistry.get(modelProviderId);
        // system 经 PromptAssembler 按段组装：角色段（表 prompt > 兜底指令 > 默认）+ 索引/纪律/约定等段；
        // 兜底角色指令收敛为角色段兜底，不再拼进 user。
        // 知识检索（RAG）：按当前 user 文本检索知识库，命中则追加【出处N】知识段——
        // retriever 为 null（禁用/单测）或无命中时原样，退化现状；aggregator 角色策略跳过；
        // 多库隔离：agent 表 knowledge 列（config 携带）解析为绑定库列表，null = 不限
        String system = promptAssembler.assemble(forAgent, fallbackSystem);
        if (knowledgeRetriever != null) {
            String knowledgeBlock = knowledgeRetriever.buildKnowledgeBlock(forAgent, sessionId, user,
                    KnowledgeRetriever.parseBinding(config != null ? config.knowledge() : null));
            if (knowledgeBlock != null && !knowledgeBlock.isBlank()) {
                system = system + "\n\n" + knowledgeBlock;
            }
        }
        ChatClient.ChatClientRequestSpec spec = client.prompt()
                .system(system)
                .user(user);
        // 记忆注入（只读，注入条件由调用方判定）：手动加载历史拼进请求消息，不挂
        // MessageChatMemoryAdvisor——该 advisor 会自动写回（before 写 user、after 写 assistant），
        // 编排中间产物入库/双写污染由此产生；会话写回统一由 ChatService 负责。
        // 手动注入同时让 ContextAssemblingAdvisor 对「历史 + 本轮」整体做归一化与预算裁剪
        // （advisor 方式下历史在裁剪之后才合并，既不受预算约束也不做 role 归一化）
        if (assembly.injectMemory()) {
            List<Message> history = memoryStore.get(sessionId);
            if (history != null && !history.isEmpty()) {
                spec.messages(history);
            }
            spec.advisors(new ContextAssemblingAdvisor(budgets.getHistoryBudget()));
        }
        // 请求级 advisor 挂载（如 lead/聚合的 PromptBudgetAdvisor）：
        // 不用 default advisor——聚合与子任务共用同一客户端，default 挂载会连坐到无关调用
        for (Advisor advisor : extraAdvisors) {
            spec.advisors(advisor);
        }
        // 请求级选项：streamUsage(true) 流式末帧回传真实 usage（OpenAI stream_options.include_usage，
        // DashScope 兼容模式与 DeepSeek 均支持）——llm_call_log 据此记真实 token。
        // model 非空时必须请求级显式指定（Registry 构建的客户端 defaultOptions 为空，否则厂商端 400），
        // 频率惩罚仅编排路径启用（model 空时不覆盖客户端默认，与存量行为一致）；
        // 输出封顶按调用方档位（0 = 不限制，不写入保持模型默认）
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().streamUsage(true);
        if (model != null && !model.isBlank()) {
            options.model(model);
            if (assembly.frequencyPenalty()) {
                options.frequencyPenalty(0.5);
            }
        }
        if (assembly.maxTokens() > 0) {
            options.maxTokens(assembly.maxTokens());
        }
        spec.options(options.build());
        // 专家工具分配：按 agent 名注入请求级工具（与客户端 defaultTools 合并）；
        // disableTools=true 跳过（幻觉工具调用的降级重试路径）。
        // 双通道统一为 ToolCallback 单通道（@Tool 注解对象经 ToolCallbacks.from 转回调，与
        // .tools 注入等价），便于 tracer/预算/延迟加载统一装饰
        if (assembly.disableTools()) {
            return spec;
        }
        ToolAssignments.ToolSet toolSet = toolAssignments == null
                ? ToolAssignments.ToolSet.EMPTY
                : toolAssignments.forAgent(forAgent);
        List<ToolCallback> tools = new ArrayList<>(toolSet.callbacks());
        if (!toolSet.annotated().isEmpty()) {
            tools.addAll(List.of(ToolCallbacks.from(toolSet.annotated().toArray())));
        }
        if (assembly.toolEmitter() != null) {
            // 追踪模式：tracer 装饰真实工具（执行起止经 emitter 发进度行，CLI 工具调用行）
            tools = ToolCallTracer.trace(tools, assembly.toolEmitter());
            if (assembly.toolCallBudget() && !tools.isEmpty()) {
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
        // load_skill 元工具（skill 索引段配套）：该 agent 有可见技能时注册——同 expand_tool 口径
        // 不经 tracer/次数额度；skill 全文自身在 SkillManager 内按 tool-result-budget 截断
        List<ToolCallback> loadSkill = skillManager == null ? List.of()
                : skillManager.loadSkillTool(forAgent).map(List::of).orElse(List.of());
        if (!loadSkill.isEmpty()) {
            List<ToolCallback> merged = new ArrayList<>(tools);
            merged.addAll(loadSkill);
            tools = merged;
        }
        if (!tools.isEmpty()) {
            spec.toolCallbacks(tools.toArray(new ToolCallback[0]));
        }
        return spec;
    }
}
