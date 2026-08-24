package com.dark.javaHarness.agent;

import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.tool.DemoTools;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.Builder;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 通用 AI Agent：使用 Spring AI ChatClient 调用大模型来完成目标。
 *
 * 模型与系统提示词通过 AgentService.getAgentConfig() 从 agent 表读取
 * （按 agent_name = "general" 匹配）：每次调用使用表中 model / prompt，
 * 表未配置或读取失败时回退默认提示词与 yaml 配置的模型。
 *
 * 多轮会话记忆基于 session + session_messages 两张表（一对一）：
 * 若 Goal 带有 sessionId，先读取该会话的上下文快照（session_messages 唯一一行）
 * 一并发送给模型；本轮 user/assistant 消息逐条追加进
 * session_messages 同一行的 content，同时更新 session.last_question。
 *
 * 已注册工具调用：模型可按需调用 DemoTools 中的工具（取时间/计算/查天气）。
 */
@Component
public class GeneralAssistantAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(GeneralAssistantAgent.class);

    private static final String AGENT_NAME = "general";
    private static final String DEFAULT_SYSTEM_PROMPT = "你是一个执行任务的 AI 助手，请直接给出简洁、可执行的完成结果。你能记住本会话之前的对话内容，回答时结合历史上下文。";

    private final ChatClient chatClient;
    private final SessionService memoryStore;
    private final AgentService agentService;

    public GeneralAssistantAgent(Builder chatClientBuilder,
                                 SessionService memoryStore,
                                 @Lazy AgentService agentService) {
        this.memoryStore = memoryStore;
        this.agentService = agentService;
        this.chatClient = chatClientBuilder
                .defaultTools(new DemoTools())  // 注册工具调用：模型可调用 DemoTools 的 @Tool 方法
                .build();
    }

    /** 返回 Agent 名称（用于注册与路由） */
    @Override
    public String name() {
        return AGENT_NAME;
    }

    /** 同步执行目标：携带会话历史调用大模型，返回完整回复并写回会话记忆 */
    @Override
    public String execute(Goal goal) {
        log.info("AI agent '{}' 开始处理目标: {}", name(), goal.objective());
        String sessionId = goal.sessionId();
        List<Message> history = memoryStore.loadContext(sessionId);

        String reply = newRequest(history, goal.objective()).call().content();
        log.info("AI agent '{}' 得到回复: {}", name(), reply);
        // 会话记忆写回不在此处做，统一由 ChatService 负责（与流式路径保持一致）
        return reply;
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
        String sessionId = goal.sessionId();
        List<Message> history = memoryStore.loadContext(sessionId);

        Flux<String> flux = newRequest(history, goal.objective())
                .stream()
                .content();
        // 真正逐 token 推送：订阅流，每来一个 token 立即回调，阻塞等待流结束
        flux.doOnNext(token -> {
                    if (onToken != null) {
                        onToken.accept(token);
                    }
                })
                .then()
                .block();
        log.info("AI agent '{}' 流式输出完成", name());
    }

    /** 组装一次请求：系统提示词 + 会话历史 + 用户目标，并按 agent 表模型覆盖模型选项 */
    private ChatClient.ChatClientRequestSpec newRequest(List<Message> history, String objective) {
        AgentConfig config = agentService.getAgentConfig(AGENT_NAME)
                .orElse(new AgentConfig(null, DEFAULT_SYSTEM_PROMPT));
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .system(config.prompt() != null ? config.prompt() : DEFAULT_SYSTEM_PROMPT)
                .messages(history)
                .user(objective);
        if (config.model() != null && !config.model().isBlank()) {
            spec.options(OpenAiChatOptions.builder().model(config.model()).build());
        }
        return spec;
    }
}
