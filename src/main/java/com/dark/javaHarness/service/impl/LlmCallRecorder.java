package com.dark.javaHarness.service.impl;

import com.dark.javaHarness.domain.LlmCallLog;
import com.dark.javaHarness.domain.entity.LlmCallLogEntity;
import com.dark.javaHarness.mapper.LlmCallLogMapper;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * LLM 调用观测记录器：把每次调用的耗时 / token 消耗异步写入 llm_call_log 表。
 *
 * <p>设计约束：<b>观测永不影响主链路</b>——落库走 boundedElastic 边界异步执行，
 * 任何异常只记 warn，不向调用方传播；调用出口（AgentChatCaller / GeneralAssistantAgent /
 * LlmRouteJudge）在 try/catch 或 doFinally 中调用本类，不因观测增加失败面。
 *
 * <p>token 口径：阻塞调用取响应 usage（真实值）；流式调用无 usage 回包，
 * 用近似估算（中文字符按 1 token、其它按 (长度+3)/4），tokensEstimated=1 标记。
 */
@Service
public class LlmCallRecorder {

    private static final Logger log = LoggerFactory.getLogger(LlmCallRecorder.class);

    private final LlmCallLogMapper mapper;

    public LlmCallRecorder(LlmCallLogMapper mapper) {
        this.mapper = mapper;
    }

    /** 异步落库一条调用记录；立即返回，内部异常仅 warn */
    public void record(LlmCallLog log1) {
        Mono.fromRunnable(() -> doInsert(log1))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(v -> { },
                        e -> log.warn("[llm-call] 观测记录落库失败（不影响主链路）：{}", e.getMessage()));
    }

    private void doInsert(LlmCallLog c) {
        LlmCallLogEntity e = new LlmCallLogEntity();
        e.setSessionId(c.sessionId());
        e.setAgentName(c.agentName());
        e.setModel(c.model());
        e.setCallKind(c.stream() ? "STREAM" : "SYNC");
        e.setStatus(c.ok() ? "OK" : "ERROR");
        e.setPromptTokens(c.promptTokens());
        e.setCompletionTokens(c.completionTokens());
        e.setTotalTokens(c.totalTokens());
        e.setTokensEstimated(c.tokensEstimated() ? 1 : 0);
        e.setDurationMs(c.durationMs());
        // 库列 VARCHAR(512)，超长截断防写入失败
        String err = c.errorMsg();
        e.setErrorMsg(err != null && err.length() > 500 ? err.substring(0, 500) : err);
        e.setCreatedAt(LocalDateTime.now());
        mapper.insert(e);
    }

    /**
     * 近似估算 token 数（与 ContextAssemblingAdvisor 同口径）：中文字符按 1 token，其它按 (长度+3)/4。
     * 供流式调用（无 usage 回包）标记估算值。
     */
    public static int estimateTokens(String text) {
        return com.dark.javaHarness.tool.TokenEstimator.estimateTokens(text);
    }

    /**
     * 展开异常原因链取「最有信息量」的错误描述，供 error_msg 落库。
     *
     * 优先取带响应体的 HTTP 异常（{@link HttpStatusCodeException}，模型供应商的
     * 报错 JSON 就在响应体里，如 401 invalid_api_key）；其余取链上第一条非空
     * message；全空兜底最外层类名。流式路径的异常常被框架层层包裹，直接取
     * 外层 getMessage() 往往为空或过于泛化（曾把供应商 4xx 报错记成 null）。
     * 超长截断由 doInsert 统一处理。
     */
    public static String describeError(Throwable error) {
        if (error == null) {
            return null;
        }
        String firstMsg = null;
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof org.springframework.web.client.HttpStatusCodeException hse) {
                String body = hse.getResponseBodyAsString();
                return "HTTP " + hse.getStatusCode().value()
                        + (body == null || body.isBlank() ? "" : " - " + body.trim());
            }
            if (firstMsg == null && t.getMessage() != null && !t.getMessage().isBlank()) {
                firstMsg = t.getMessage();
            }
        }
        return firstMsg != null ? firstMsg : error.getClass().getSimpleName();
    }
}
