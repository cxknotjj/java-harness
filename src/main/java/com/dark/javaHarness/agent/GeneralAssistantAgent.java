package com.dark.javaHarness.agent;

import com.dark.javaHarness.core.agent.Agent;
import com.dark.javaHarness.core.goal.Goal;
import com.dark.javaHarness.core.memory.ChatMemoryStore;
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
 * 自动携带多轮会话记忆：若 Goal 带有 sessionId，则先从 MySQL 读取该会话的历史消息
 * 一并发送给模型，再把本轮 user/assistant 消息持久化回会话，实现上下文连贯。
 */
@Component
public class GeneralAssistantAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(GeneralAssistantAgent.class);

    private static final String SYSTEM_PROMPT = "你是一个执行任务的 AI 助手，请直接给出简洁、可执行的完成结果。你能记住本会话之前的对话内容，回答时结合历史上下文。";

    private final ChatClient chatClient;
    private final ChatMemoryStore memoryStore;

    public GeneralAssistantAgent(Builder chatClientBuilder, ChatMemoryStore memoryStore) {
        this.chatClient = chatClientBuilder.build();
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
        List<Message> history = (sessionId == null || sessionId.isBlank())
                ? List.of()
                : memoryStore.get(sessionId);

        String reply = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .messages(history)
                .user(goal.objective())
                .call()
                .content();
        log.info("AI agent '{}' 得到回复: {}", name(), reply);

        // 持久化本轮会话：历史 + 本次问题 + 本次回答，供下一轮使用
        if (sessionId != null && !sessionId.isBlank()) {
            memoryStore.add(sessionId, List.of(
                    new UserMessage(goal.objective()),
                    new AssistantMessage(reply)));
        }
        return reply;
    }
}