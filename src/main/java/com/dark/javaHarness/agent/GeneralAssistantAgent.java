package com.dark.javaHarness.agent;

import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.tool.DemoTools;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.Builder;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

/**
 * 通用 AI Agent：使用 Spring AI ChatClient 调用大模型来完成目标。
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

    private static final String SYSTEM_PROMPT = "你是一个执行任务的 AI 助手，请直接给出简洁、可执行的完成结果。你能记住本会话之前的对话内容，回答时结合历史上下文。";

    private final ChatClient chatClient;
    private final SessionService memoryStore;

    public GeneralAssistantAgent(Builder chatClientBuilder, SessionService memoryStore) {
        this.chatClient = chatClientBuilder
                .defaultTools(new DemoTools())  // 注册工具调用：模型可调用 DemoTools 的 @Tool 方法
                .build();
        this.memoryStore = memoryStore;
    }

    /** 返回 Agent 名称（用于注册与路由） */
    @Override
    public String name() {
        return "general";
    }

    /** 同步执行目标：携带会话历史调用大模型，返回完整回复并写回会话记忆 */
    @Override
    public String execute(Goal goal) {
        log.info("AI agent '{}' 开始处理目标: {}", name(), goal.objective());
        String sessionId = goal.sessionId();
        List<Message> history = memoryStore.loadContext(sessionId);

        String reply = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .messages(history)
                .user(goal.objective())
                .call()
                .content();
        log.info("AI agent '{}' 得到回复: {}", name(), reply);
        // 会话记忆写回不在此处做，统一由 ChatService 负责（与流式路径保持一致）
        return reply;
    }

    /**
     * 流式执行：用 ChatClient.stream() 逐 token 回调 onToken，并返回完整文本。
     * 注意：记忆持久化不在此处做，交给 ChatService 在流结束后统一写回
     * （保证写入的 assistant 内容与最终完整回复一致）。
     */
    @Override
    public String executeStream(Goal goal, Consumer<String> onToken) {
        log.info("AI agent '{}' 开始流式处理目标: {}", name(), goal.objective());
        String sessionId = goal.sessionId();
        List<Message> history = memoryStore.loadContext(sessionId);

        String full = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .messages(history)
                .user(goal.objective())
                .stream()
                .content()
                .doOnNext(token -> {
                    if (onToken != null) {
                        onToken.accept(token);
                    }
                })
                .collectList()
                .block()
                .stream()
                .collect(Collectors.joining());
        log.info("AI agent '{}' 流式输出完成，长度={}", name(), full.length());
        return full;
    }
}