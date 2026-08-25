package com.dark.javaHarness.agent;

import com.dark.javaHarness.config.ChatClientRegistry;
import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.graph.MySqlCheckpointer;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.SessionService;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.openai.OpenAiChatOptions;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 由 Spring AI Alibaba Graph 承载的通用对话 Agent（对话级替换执行层）。
 *
 * <p>与 {@link GeneralAssistantAgent} 相比，执行逻辑不再“直调 ChatClient”，
 * 而是包装成一个有状态的 StateGraph 工作流：
 * <pre>
 * START → prepare(读 agent 配置 + 加载会话历史 + 注入 goal)
 *              └─→ chat(调用 LLM，流式时逐 token 回调 onToken)
 *                     └─→ END
 * </pre>
 *
 * <p>对外行为与 GeneralAssistantAgent 完全一致（execute/executeStream/name），
 * 因此 CLI/HTTP/ChatService 等调用链路无需任何改动，仅由 ChatAgentConfig
 * 将 general 指向本实现，即可让 Graph 真正参与线上聊天执行。
 *
 * <p>状态机每次执行基于单次 Goal 即时编译（闭包含有本次的 ChatClient 与 onToken），
 * 保证并发安全——不同 goal 各占独立图实例。
 */
public class GraphAssistantAgent implements Agent {

    /** 图节点 id */
    private static final String NODE_PREPARE = "prepare";
    private static final String NODE_CHAT = "chat";
    /** 状态键 */
    private static final String KEY_OBJECTIVE = "objective";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_REPLY = "reply";

    private static final Logger log = LoggerFactory.getLogger(GraphAssistantAgent.class);

    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是一个执行任务的 AI 助手，请直接给出简洁、可执行的完成结果。你能记住本会话之前的对话内容，回答时结合历史上下文。";

    private final String agentName;
    private final ChatClientRegistry clientRegistry;
    private final SessionService memoryStore;
    private final AgentService agentService;
    private final DataSource dataSource;

    public GraphAssistantAgent(String agentName,
                               ChatClientRegistry clientRegistry,
                               SessionService memoryStore,
                               AgentService agentService,
                               DataSource dataSource) {
        this.agentName = agentName;
        this.clientRegistry = clientRegistry;
        this.memoryStore = memoryStore;
        this.agentService = agentService;
        this.dataSource = dataSource;
    }

    @Override
    public String name() {
        return agentName;
    }

    @Override
    public String execute(Goal goal) {
        return run(goal, null);
    }

    @Override
    public void executeStream(Goal goal, Consumer<String> onToken) {
        run(goal, onToken);
    }

    /** 编排一次执行：返回完整回复；onToken 非空时走流式逐 token 回调。 */
    private String run(Goal goal, Consumer<String> onToken) {
        log.info("[graph-agent '{}'] 开始处理目标: {}", name(), goal.objective());
        String sessionId = goal.sessionId();
        List<Message> history = memoryStore.loadContext(sessionId);

        String reply;
        try {
            CompiledGraph graph = compile(history, onToken);
            Map<String, Object> initialState = Map.of(
                    KEY_OBJECTIVE, goal.objective(),
                    KEY_HISTORY, history);
            OverAllState state = graph.invoke(initialState)
                    .orElseThrow(() -> new IllegalStateException("graph 未返回任何状态"));
            reply = state.value(KEY_REPLY, "");
        } catch (GraphStateException e) {
            throw new IllegalStateException("graph 编排失败", e);
        }
        log.info("[graph-agent '{}'] 执行完成，回复长度={}", name(), reply.length());
        return reply;
    }

    /** 构建本次执行的图：prepare 节点读配置/历史写入状态，chat 节点调 LLM。 */
    private CompiledGraph compile(List<Message> history, Consumer<String> onToken) throws GraphStateException {
        // 读取 agent 配置（prompt + model），失败则用默认值
        AgentConfig config = agentService.getAgentConfig(agentName)
                .orElse(new AgentConfig(null, DEFAULT_SYSTEM_PROMPT));
        String model = config.model();
        // Registry：凭 model 取对应厂商 ChatClient（未匹配回退默认 DashScope）
        ChatClient client = clientRegistry.get(model);

        KeyStrategyFactory keyStrategyFactory = () -> {
            Map<String, KeyStrategy> strategies = new java.util.HashMap<>();
            strategies.put(KEY_OBJECTIVE, KeyStrategy.REPLACE);
            strategies.put(KEY_HISTORY, KeyStrategy.REPLACE);
            strategies.put(KEY_REPLY, KeyStrategy.REPLACE);
            return strategies;
        };

        StateGraph graph = new StateGraph("chat-" + agentName, keyStrategyFactory);

        // prepare：读取状态中的 objective + history，组装一次请求参数
        graph.addNode(NODE_PREPARE, node_async(state -> {
            String objective = state.value(KEY_OBJECTIVE, "");
            List<Message> historyMsgs = state.value(KEY_HISTORY, List.class).orElseGet(List::of);
            return Map.of(
                    KEY_OBJECTIVE, objective,
                    KEY_HISTORY, historyMsgs,
                    // 将本次入参透传，供 chat 节点复用，避免重复读
                    "prompt", config.prompt() != null ? config.prompt() : DEFAULT_SYSTEM_PROMPT,
                    "model", model);
        }));

        // chat：真正调用 LLM，流式时逐 token 回调 onToken
        graph.addNode(NODE_CHAT, node_async(state -> {
            String objective = state.value(KEY_OBJECTIVE, "");
            List<Message> historyMsgs = state.value(KEY_HISTORY, List.class).orElseGet(List::of);
            String prompt = state.value("prompt", DEFAULT_SYSTEM_PROMPT);
            String modelEach = state.value("model", (String) null);

            ChatClient.ChatClientRequestSpec spec = client.prompt()
                    .system(prompt)
                    .messages(historyMsgs)
                    .user(objective);
            if (modelEach != null && !modelEach.isBlank()) {
                spec.options(OpenAiChatOptions.builder().model(modelEach).build());
            }

            String reply;
            if (onToken != null) {
                // 真正逐 token：订阅 stream，回调查看并拼装完整结果
                StringBuilder full = new StringBuilder();
                spec.stream().content()
                        .doOnNext(token -> {
                            full.append(token);
                            onToken.accept(token);
                        })
                        .then()
                        .block();
                reply = full.toString();
            } else {
                reply = spec.call().content();
            }
            return Map.of(KEY_REPLY, reply);
        }));

        graph.addEdge(START, NODE_PREPARE)
                .addEdge(NODE_PREPARE, NODE_CHAT)
                .addEdge(NODE_CHAT, END);

        // 接入 Checkpointer：状态持久化到 GRAPH_THREAD / GRAPH_CHECKPOINT，支持断点恢复
        MysqlSaver saver = MySqlCheckpointer.get(dataSource);
        SaverConfig saverConfig = SaverConfig.builder().register(saver).build();
        return graph.compile(CompileConfig.builder()
                .saverConfig(saverConfig)
                .build());
    }
}