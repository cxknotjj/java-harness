package com.dark.javaHarness.service;

import com.dark.javaHarness.domain.dto.ChatRequest;
import com.dark.javaHarness.domain.dto.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * 聊天用例服务：编排多轮会话聊天（会话建档 + Agent 执行 + 响应组装）。
 */
public interface ChatService {

    /** 处理一次聊天请求（同步执行）。 */
    ChatResponse chat(ChatRequest request);

    /**
     * 响应式流式聊天：返回 text/event-stream 格式的 {@link Flux<String>}。
     * 无 sessionId 时自动创建新会话；逐 token 产出 {@code data: <token>}，
     * token 流结束后产出 {@code data: [DONE]}，末尾产出 meta 事件；
     * 出错时产出 error 事件后终止。DB 操作隔离在 boundedElastic 上执行。
     */
    Flux<String> streamReactive(ChatRequest request);

    /**
     * 复杂编排断点续跑：按 goalId 复用既有 goal，从上次检查点继续执行（multi-agent）。
     * goal 不存在抛 400（IllegalArgumentException）；仍在 RUNNING 抛 409
     * （{@code ResumeConflictException}，防双跑）。SSE 输出语义同 {@link #streamReactive}。
     */
    Flux<String> resume(String goalId);
}