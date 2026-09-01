package com.dark.javaHarness.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;

/**
 * ModelQuotaException 单测：账户级硬错误（402 余额不足 / 403 配额耗尽）的识别与
 * 人话转换；非配额类 4xx（400/401）不误报。
 */
class ModelQuotaExceptionTest {

    private static final String DEEPSEEK_402 =
            "402 - {\"error\":{\"message\":\"Insufficient Balance\",\"code\":\"invalid_request_error\"}}";
    private static final String QWEN_403_FREE_TIER =
            "403 - {\"error\":{\"message\":\"Free quota exhausted.\",\"code\":\"AllocationQuota.FreeTierOnly\"}}";

    @Test
    void matches_402InsufficientBalance_true() {
        NonTransientAiException e = new NonTransientAiException(DEEPSEEK_402);
        assertTrue(ModelQuotaException.matches(e), "402 余额不足应命中");
        assertTrue(ModelQuotaException.matches(new RuntimeException("wrap", e)), "cause 链剥壳也应命中");
    }

    @Test
    void matches_403QuotaExhausted_true() {
        NonTransientAiException e = new NonTransientAiException(QWEN_403_FREE_TIER);
        assertTrue(ModelQuotaException.matches(e), "403 配额耗尽（FreeTierOnly）应命中");
    }

    @Test
    void matches_403WithoutQuotaKeyword_false() {
        NonTransientAiException e = new NonTransientAiException(
                "403 - {\"error\":{\"message\":\"permission denied\"}}");
        assertFalse(ModelQuotaException.matches(e), "403 但无配额关键词不应命中");
    }

    @Test
    void matches_other4xx_false() {
        assertFalse(ModelQuotaException.matches(
                new NonTransientAiException("400 - invalid param")), "400 参数错误不应命中");
        assertFalse(ModelQuotaException.matches(
                new NonTransientAiException("401 - invalid api key")), "401 鉴权失败不应命中");
        assertFalse(ModelQuotaException.matches(
                new RuntimeException("普通异常")), "非模型异常不应命中");
    }

    @Test
    void from_402_buildsHumanFriendlyMessage() {
        ModelQuotaException ex = ModelQuotaException.from(
                new NonTransientAiException(DEEPSEEK_402), "deepseek-v4-flash");
        assertEquals(402, ex.httpStatus());
        assertEquals("deepseek-v4-flash", ex.model());
        assertTrue(ex.getMessage().contains("deepseek-v4-flash"), "消息应含模型名");
        assertTrue(ex.getMessage().contains("余额不足"), "消息应说明余额问题");
        assertTrue(ex.getMessage().contains("Insufficient Balance"), "消息应保留厂商原始片段");
        assertFalse(ex.getMessage().startsWith("402"), "不应带 '<status> - ' 前缀");
    }

    @Test
    void from_403_buildsQuotaHint() {
        ModelQuotaException ex = ModelQuotaException.from(
                new NonTransientAiException(QWEN_403_FREE_TIER), "qwen3.7-plus");
        assertEquals(403, ex.httpStatus());
        assertTrue(ex.getMessage().contains("配额耗尽"), "403 应提示配额问题");
    }
}
