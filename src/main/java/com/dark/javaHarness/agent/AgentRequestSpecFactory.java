package com.dark.javaHarness.agent;

import com.dark.javaHarness.advisor.ContextAssemblingAdvisor;
import com.dark.javaHarness.advisor.LlmRequestLogAdvisor;
import com.dark.javaHarness.config.ContextBudgetProperties;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.knowledge.KnowledgeRetriever;
import com.dark.javaHarness.prompt.PromptAssembler;
import com.dark.javaHarness.prompt.SkillManager;
import com.dark.javaHarness.prompt.ToolLazyManager;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.service.impl.LlmCallRecorder;
import com.dark.javaHarness.tool.DefaultToolDecorators;
import com.dark.javaHarness.tool.ToolAssignments;
import com.dark.javaHarness.tool.ToolCallbackDecorator;
import com.dark.javaHarness.tool.ToolDecorationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
 * 请求级选项（model / streamUsage / maxTokens 档位）与工具注入装饰（可插拔装饰链，
 * 默认 观测→预算→懒加载→元工具，见 {@link DefaultToolDecorators}）。
 *
 * <p>两条路径的差异点经 {@link Assembly} 显式声明（频率惩罚与工具次数预算仅编排路径
 * 启用、记忆注入条件由调用方策略判定、maxTokens 档位由调用方解析），工厂本身不感知
 * 编排角色语义。
 */
final class AgentRequestSpecFactory {

    /**
     * 单次请求的组装差异项（两条路径各自声明）：
     * <ul>
     *   <li>{@code toolEmitter}：SSE 进度行发射器，经 ToolDecorationContext 传入装饰链——
     *       观测装饰器（emitter/recorder 任一非空即装饰）用其发工具执行起止进度行，
     *       本字段本身不再决定是否装饰工具</li>
     *   <li>{@code disableTools}：true 跳过全部工具注入（幻觉工具调用的降级重试路径，
     *       在装饰链之前直接返回）</li>
     *   <li>{@code injectMemory}：是否只读注入会话历史（编排路径按 MemoryPolicy 判定仅 lead；
     *       路径 A 恒注入）</li>
     *   <li>{@code frequencyPenalty}：频率惩罚（长报告聚合复读抑制，仅编排路径启用）</li>
     *   <li>{@code toolCallBudget}：工具次数/结果硬预算（仅编排路径启用；经 Context 传给
     *       ToolBudgetDecorator 判定，不再依赖 emitter 非空）</li>
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
    private final SessionService memoryStore;
    private final ContextBudgetProperties budgets;
    /** 知识检索器（RAG 注入面）；null 时零行为变化（知识库禁用/单测场景，沿用 skillManager==null 约定） */
    private final KnowledgeRetriever knowledgeRetriever;
    /** 可插拔工具装饰链（按 Order 升序应用；默认链见 {@link DefaultToolDecorators#defaults}） */
    private final List<ToolCallbackDecorator> decorators;

    AgentRequestSpecFactory(ChatClientRegistry clientRegistry,
                            PromptAssembler promptAssembler,
                            ToolAssignments toolAssignments,
                            ToolLazyManager lazyTools,
                            SkillManager skillManager,
                            SessionService memoryStore,
                            ContextBudgetProperties budgets) {
        this(clientRegistry, promptAssembler, toolAssignments, lazyTools, skillManager,
                memoryStore, budgets, null, null,
                DefaultToolDecorators.defaults(lazyTools, skillManager, budgets, null));
    }

    AgentRequestSpecFactory(ChatClientRegistry clientRegistry,
                            PromptAssembler promptAssembler,
                            ToolAssignments toolAssignments,
                            ToolLazyManager lazyTools,
                            SkillManager skillManager,
                            SessionService memoryStore,
                            ContextBudgetProperties budgets,
                            KnowledgeRetriever knowledgeRetriever) {
        this(clientRegistry, promptAssembler, toolAssignments, lazyTools, skillManager,
                memoryStore, budgets, knowledgeRetriever, null,
                DefaultToolDecorators.defaults(lazyTools, skillManager, budgets, null));
    }

    /** recorder 为工具调用观测记录器（tool_call_log 落库面），null 时不落库（单测场景） */
    AgentRequestSpecFactory(ChatClientRegistry clientRegistry,
                            PromptAssembler promptAssembler,
                            ToolAssignments toolAssignments,
                            ToolLazyManager lazyTools,
                            SkillManager skillManager,
                            SessionService memoryStore,
                            ContextBudgetProperties budgets,
                            KnowledgeRetriever knowledgeRetriever,
                            LlmCallRecorder recorder) {
        this(clientRegistry, promptAssembler, toolAssignments, lazyTools, skillManager,
                memoryStore, budgets, knowledgeRetriever, recorder,
                DefaultToolDecorators.defaults(lazyTools, skillManager, budgets, recorder));
    }

    /**
     * 全参构造：decorators 为可插拔工具装饰链（按 Order 升序应用，
     * 默认链见 {@link DefaultToolDecorators#defaults}）
     */
    AgentRequestSpecFactory(ChatClientRegistry clientRegistry,
                            PromptAssembler promptAssembler,
                            ToolAssignments toolAssignments,
                            ToolLazyManager lazyTools,
                            SkillManager skillManager,
                            SessionService memoryStore,
                            ContextBudgetProperties budgets,
                            KnowledgeRetriever knowledgeRetriever,
                            LlmCallRecorder recorder,
                            List<ToolCallbackDecorator> decorators) {
        this.clientRegistry = clientRegistry;
        this.promptAssembler = promptAssembler;
        this.toolAssignments = toolAssignments;
        this.memoryStore = memoryStore;
        this.budgets = budgets;
        this.knowledgeRetriever = knowledgeRetriever;
        this.decorators = decorators;
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
        // config 已由调用方查好传入：经三参 assemble 复载下传，角色段不再重复查表
        String system = promptAssembler.assemble(forAgent, fallbackSystem, config);
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
        // 请求发起观测（innermost，见类注释）：每次真实模型请求（含重试）记一条
        // 专家类型/模型/上下文条数与字符量——llm_call_log 只在结束时落行，在途请求
        // 此前无任何痕迹可查
        spec.advisors(new LlmRequestLogAdvisor(forAgent, sessionId));
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
        // .tools 注入等价），便于装饰链统一装饰
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
        // 装饰链应用（可插拔）：按 Order 升序逐层装饰——观测(100)→预算(200)→懒加载(300)→
        // 元工具(400)；各装饰器自判适用条件（不适用原样直通）。顺序契约见 ToolCallbackDecorator。
        ToolDecorationContext ctx = new ToolDecorationContext(
                forAgent, sessionId, assembly.toolEmitter(), assembly.toolCallBudget());
        for (ToolCallbackDecorator decorator : decorators) {
            tools = Objects.requireNonNull(decorator.decorate(ctx, tools),
                    decorator.getClass().getSimpleName() + " 返回 null（禁止，组装期编程错误）");
        }
        if (!tools.isEmpty()) {
            spec.toolCallbacks(tools.toArray(new ToolCallback[0]));
        }
        return spec;
    }
}
