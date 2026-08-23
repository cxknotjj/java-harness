package com.dark.javaHarness.controller;

import com.dark.javaHarness.dto.ChatRequest;
import com.dark.javaHarness.dto.ChatResponse;
import com.dark.javaHarness.service.ChatService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    public ChatResponse chat(@RequestBody ChatRequest request) {
        return chatService.chat(request);
    }

    /**
     * 流式聊天接口（SSE，带多轮会话记忆）
     * POST /api/chat/stream  Body: {"message": "你好", "sessionId": "1"}
     * 响应为 text/event-stream：逐 token data: <文本>，结束 data: [DONE]，
     * 末尾 event: meta data: {sessionId,newSession,goalId,status}。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request) {
        return chatService.stream(request);
    }
}