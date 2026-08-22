package com.dark.javaHarness.web;

import com.dark.javaHarness.core.agent.AgentService;
import com.dark.javaHarness.core.goal.Goal;
import com.dark.javaHarness.core.session.SessionMemoryStore;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 千问（Qwen）聊天接口。
 * 走 harness 编排层（AgentService -> general Agent -> Spring AI ChatClient
 * -> DashScope qwen-plus），聊天记录留存于 session + session_messages 表。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AgentService agentService;
    private final SessionMemoryStore sessionMemoryStore;

    public ChatController(AgentService agentService, SessionMemoryStore sessionMemoryStore) {
        this.agentService = agentService;
        this.sessionMemoryStore = sessionMemoryStore;
    }

    /** 聊天请求体：message 必填，sessionId 可选（为空时自动创建新会话并在响应中返回） */
    public record ChatRequest(String message, String sessionId) {
    }

    /**
     * 同步聊天接口（带多轮会话记忆）
     * POST /api/chat  Body: {"message": "你好", "sessionId": "1"}
     * 首次不传 sessionId，响应中返回；后续携带即可延续同一会话上下文。
     */
    @PostMapping
    public Map<String, Object> chat(@RequestBody ChatRequest request) {
        if (request.message() == null || request.message().isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }

        // 无 sessionId 时自动建档（session 表），会话名取首条提问
        String sessionId = request.sessionId();
        boolean newSession = false;
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = sessionMemoryStore.createSession("anonymous", request.message());
            newSession = true;
        }

        // 通过 harness 编排层同步执行：goal 生命周期与 summary 均会留存
        Goal goal = agentService.executeSync("general", request.message(), sessionId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("newSession", newSession);
        result.put("goalId", goal.id());
        result.put("status", goal.status().name());
        result.put("reply", goal.summary());
        if (goal.status() == Goal.Status.FAILED) {
            result.put("error", goal.summary());
        }
        return result;
    }
}