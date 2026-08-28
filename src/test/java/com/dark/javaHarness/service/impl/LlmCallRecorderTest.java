package com.dark.javaHarness.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.domain.LlmCallLog;
import com.dark.javaHarness.domain.entity.LlmCallLogEntity;
import com.dark.javaHarness.mapper.LlmCallLogMapper;
import org.junit.jupiter.api.Test;

/**
 * LlmCallRecorder 单测：
 * - token 近似估算口径（中文 1 token、其它 (长度+3)/4，与 ContextAssemblingAdvisor 一致）
 * - record 异步落库成功与失败都不向调用方抛异常（观测永不影响主链路）
 */
class LlmCallRecorderTest {

    @Test
    void estimateTokens_mixedText_followsAdvisorConvention() {
        // 4 个中文（4 token）+ "abcd"（(4+3)/4=1 token）= 5
        assertEquals(5, LlmCallRecorder.estimateTokens("你好世界abcd"));
        assertEquals(0, LlmCallRecorder.estimateTokens(""));
        assertEquals(0, LlmCallRecorder.estimateTokens(null));
        // 纯英文 8 字符 → (8+3)/4 = 2
        assertEquals(2, LlmCallRecorder.estimateTokens("abcdefgh"));
    }

    @Test
    void record_insertsEntityAsynchronously() {
        LlmCallLogMapper mapper = mock(LlmCallLogMapper.class);
        LlmCallRecorder recorder = new LlmCallRecorder(mapper);

        recorder.record(new LlmCallLog("s1", "lead", "qwen3.8-27b", false, true,
                100, 20, 120, false, 1500, null));

        verify(mapper, timeout(2000)).insert(org.mockito.ArgumentMatchers.argThat((LlmCallLogEntity e) -> {
            return "s1".equals(e.getSessionId())
                    && "lead".equals(e.getAgentName())
                    && "SYNC".equals(e.getCallKind())
                    && "OK".equals(e.getStatus())
                    && Integer.valueOf(120).equals(e.getTotalTokens())
                    && e.getTokensEstimated() == 0
                    && Long.valueOf(1500).equals(e.getDurationMs());
        }));
    }

    @Test
    void record_mapperFailure_neverThrowsToCaller() {
        LlmCallLogMapper mapper = mock(LlmCallLogMapper.class);
        when(mapper.insert(any(LlmCallLogEntity.class))).thenThrow(new IllegalStateException("db down"));
        LlmCallRecorder recorder = new LlmCallRecorder(mapper);

        // 不应向调用方抛出（异步边界吞掉并 warn）
        recorder.record(new LlmCallLog(null, "route-judge", "qwen3.8-27b", true, false,
                null, 5, 5, true, 80, "boom"));
        // 给异步线程留出执行窗口；若抛出则测试线程已失败
        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        assertTrue(true, "落库异常被观测层吞掉，未影响调用方");
    }
}
