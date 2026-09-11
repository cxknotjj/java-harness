package com.dark.javaHarness.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;

/**
 * ModelAuthException 单测：鉴权失败（401）的识别与可执行指引转换；
 * 非 401 的 4xx（400/402/403）不误报（402/403 归 ModelQuotaException 口径）。
 */
class ModelAuthExceptionTest {

    private static final String DASHSCOPE_401 =
            "401 - {\"error\":{\"message\":\"Authentication Fails, Your api key: ****invalid\",\"code\":\"invalid_api_key\"}}";

    @Test
    void matches_401_true() {
        NonTransientAiException e = new NonTransientAiException(DASHSCOPE_401);
        assertTrue(ModelAuthException.matches(e), "401 鉴权失败应命中");
        assertTrue(ModelAuthException.matches(new RuntimeException("wrap", e)), "cause 链剥壳也应命中");
    }

    @Test
    void matches_other4xx_false() {
        assertFalse(ModelAuthException.matches(
                new NonTransientAiException("400 - invalid param")), "400 参数错误不应命中");
        assertFalse(ModelAuthException.matches(
                new NonTransientAiException("402 - Insufficient Balance")), "402 归配额口径，不应命中");
        assertFalse(ModelAuthException.matches(
                new NonTransientAiException("403 - quota exhausted")), "403 归配额口径，不应命中");
        assertFalse(ModelAuthException.matches(
                new RuntimeException("普通异常")), "非模型异常不应命中");
    }

    @Test
    void from_401_buildsActionableMessage() {
        ModelAuthException ex = ModelAuthException.from(
                new NonTransientAiException(DASHSCOPE_401), "deepseek-v4-flash");
        assertEquals(401, ex.httpStatus());
        assertEquals("deepseek-v4-flash", ex.model());
        String msg = ex.getMessage();
        assertTrue(msg.contains("deepseek-v4-flash"), "消息应含模型名");
        assertTrue(msg.contains("鉴权失败"), "消息应说明是鉴权问题");
        assertTrue(msg.contains("API key 环境变量") && msg.contains("重启服务"), "消息应含可执行下一步");
        assertTrue(msg.contains("README"), "消息应指向文档入口");
        assertTrue(msg.contains("invalid_api_key"), "消息应保留厂商原始片段");
        assertFalse(msg.startsWith("401"), "不应带 '<status> - ' 前缀");
    }

    @Test
    void from_nullModel_usesDefaultValue() {
        ModelAuthException ex = ModelAuthException.from(
                new NonTransientAiException(DASHSCOPE_401), null);
        assertTrue(ex.getMessage().contains("'默认'"), "模型名为空时应显示「默认」");
    }
}
