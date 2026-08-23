package com.dark.javaHarness.agent;

import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.tool.DemoTools;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.Builder;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
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

    @Override
    public String name() {
        return "general";
    }

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

        // 持久化本轮会话：本轮 user/assistant 消息逐条追加进 session_messages 该会话唯一一行
        if (sessionId != null && !sessionId.isBlank()) {
            memoryStore.saveContext(sessionId, new UserMessage(goal.objective()));
            memoryStore.saveContext(sessionId, new AssistantMessage(reply));
            memoryStore.touchSession(sessionId, goal.objective());
        }
        return reply;
    }
}