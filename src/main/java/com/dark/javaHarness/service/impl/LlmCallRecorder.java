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
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int tokens = 0;
        for (int i = 0; i < text.length(); i++) {
            tokens += text.charAt(i) > 0x2E80 ? 1 : 0; // CJK 及全角区按 1 token
        }
        int other = text.length() - tokens;
        return tokens + (other + 3) / 4;
    }
}
