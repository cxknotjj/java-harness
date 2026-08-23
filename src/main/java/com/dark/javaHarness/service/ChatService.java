package com.dark.javaHarness.service;

import com.dark.javaHarness.dto.ChatRequest;
import com.dark.javaHarness.dto.ChatResponse;
import com.dark.javaHarness.enums.ExecutionType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 聊天用例服务：编排多轮会话聊天（会话建档 + Agent 执行 + 响应组装）。
 */
public interface ChatService {

    /** 处理一次聊天请求（默认同步执行）。 */
    ChatResponse chat(ChatRequest request);

    /**
     * 处理一次聊天请求，按执行类型调用 AgentService 对应方法：
     * - SYNC：无 sessionId 走 executeSync(2参)，有 sessionId 走 executeSync(3参)
     * - STREAM：走 executeStream，逐段回调后收集为完整结果返回
     */
    ChatResponse chat(ChatRequest request, ExecutionType type);

    /**
     * 流式聊天：返回 SSE 流。
     * 无 sessionId 时自动创建新会话；逐 token 推送给 SseEmitter；
     * 流结束后把完整回复写回会话记忆，并发送 meta 事件。
     */
    SseEmitter stream(ChatRequest request);
}