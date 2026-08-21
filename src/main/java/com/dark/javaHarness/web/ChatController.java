package com.dark.javaHarness.web;

import com.dark.javaHarness.core.agent.AgentService;
import com.dark.javaHarness.core.goal.Goal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 千问（Qwen）聊天接口。
 * 走 harness 编排层（AgentService -> general Agent -> Spring AI ChatClient
 * -> DashScope qwen-plus），聊天记录同时以 Goal 形式留存可查。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AgentService agentService;

    public ChatController(AgentService agentService) {
        this.agentService = agentService;
    }

    /** 聊天请求体：message 必填，sessionId 可选（用于多轮会话记忆） */
    public record ChatRequest(String message, String sessionId) {
    }

    /**
     * 同步聊天接口
     * POST /api/chat  Body: {"message": "你好"}
     */
    @PostMapping
    public Map<String, Object> chat(@RequestBody ChatRequest request) {
        if (request.message() == null || request.message().isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        // 通过 harness 编排层同步执行：goal 生命周期与 summary 均会留存
        // sessionId 用于多轮会话记忆（历史消息自动带入并持久化）
        Goal goal = agentService.executeSync("general", request.message(), request.sessionId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("goalId", goal.id());
        result.put("status", goal.status().name());
        result.put("reply", goal.summary());
        if (goal.status() == Goal.Status.FAILED) {
            result.put("error", goal.summary());
        }
        return result;
    }
}