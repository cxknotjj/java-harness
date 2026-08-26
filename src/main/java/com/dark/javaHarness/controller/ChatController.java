package com.dark.javaHarness.controller;

import com.dark.javaHarness.domain.dto.ChatRequest;
import com.dark.javaHarness.domain.dto.ChatResponse;
import com.dark.javaHarness.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 千问（Qwen）聊天接口（表现层，纯转发）。
 * 业务编排在 ChatService：AgentService -> general Agent -> Spring AI ChatClient
 * -> DashScope qwen-plus，聊天记录留存于 session + session_messages 表。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 同步聊天接口（带多轮会话记忆）
     * POST /api/chat  Body: {"message": "你好", "sessionId": "1"}
     * 首次不传 sessionId，响应中返回；后续携带即可延续同一会话上下文。
     */
    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return chatService.chat(request);
    }

    /**
     * 流式聊天接口（带多轮会话记忆）
     * POST /api/chat/stream  Body: {"message": "你好", "sessionId": "1"}
     * 响应为 text/plain，每个 Flux 元素输出独立一行（末尾追加 {@code \n}）：
     * 逐 token data: <文本>，结束 data: [DONE]，
     * 末尾 event: meta 与 data: {sessionId,newSession,goalId,status}。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_PLAIN_VALUE)
    public Flux<String> stream(@Valid @RequestBody ChatRequest request) {
        return chatService.streamReactive(request).map(s -> s + "\n");
    }
}