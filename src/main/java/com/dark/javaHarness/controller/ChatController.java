package com.dark.javaHarness.controller;

import com.dark.javaHarness.domain.dto.ChatRequest;
import com.dark.javaHarness.domain.dto.ChatResponse;
import com.dark.javaHarness.domain.dto.GoalStatusView;
import com.dark.javaHarness.service.ChatService;
import com.dark.javaHarness.service.GoalService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final GoalService goalService;

    public ChatController(ChatService chatService, GoalService goalService) {
        this.chatService = chatService;
        this.goalService = goalService;
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
     * 逐 token event: token + data: &lt;文本&gt;，结束 event: token + data: [DONE]，
     * 进度 event: progress + data: {stage,detail}，
     * 末尾 event: meta 与 data: {sessionId,newSession,goalId,status}。
     * 注意：token 必须显式声明 event（SSE event 字段粘滞，否则会被误归入上一个事件）。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_PLAIN_VALUE)
    public Flux<String> stream(@Valid @RequestBody ChatRequest request) {
        return chatService.streamReactive(request).map(s -> s + "\n");
    }

    /**
     * 复杂编排断点续跑：按 goalId 从上次检查点继续执行 multi-agent 编排。
     * POST /api/chat/resume?goalId=&lt;id&gt;  响应格式与 /api/chat/stream 一致。
     * goal 不存在返回 400；仍在执行中返回 409；无检查点时流内发 error 事件。
     */
    @PostMapping(value = "/resume", produces = MediaType.TEXT_PLAIN_VALUE)
    public Flux<String> resume(@RequestParam("goalId") String goalId) {
        return chatService.resume(goalId).map(s -> s + "\n");
    }

    /**
     * 查询 goal 状态：GET /api/chat/goal-status?goalId=&lt;id&gt; → {"goalId":"...","status":"RUNNING"}。
     * CLI 启动恢复续跑记录时向服务端确认任务是否已完成（已完成则不再提示 /resume）。
     * goal 不存在返回 404。
     */
    @GetMapping("/goal-status")
    public GoalStatusView goalStatus(@RequestParam("goalId") String goalId) {
        return goalService.get(goalId)
                .map(g -> new GoalStatusView(g.id(), g.status().name()))
                .orElseThrow(() -> new IllegalArgumentException("目标不存在: " + goalId));
    }
}