package com.dark.javaHarness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * LlmRetry 单位测试：可重试 / 不可重试判定、指数退避后成功、重试耗尽抛错。
 */
class LlmRetryTest {

    private final LlmRetry retry = new LlmRetry(3, 1); // 最多 3 次、退避基准 1ms 加速用例

    // ---- isRetryable 判定 ----

    @Test
    void serverError5xx_isRetryable() {
        RuntimeException e = new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE);
        assertTrue(LlmRetry.isRetryable(e), "5xx 应可重试");
    }

    @Test
    void rateLimit429_isRetryable() {
        RuntimeException e = new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS);
        assertTrue(LlmRetry.isRetryable(e), "429 限流应可重试");
    }

    @Test
    void timeout408_isRetryable() {
        RuntimeException e = new HttpClientErrorException(HttpStatus.REQUEST_TIMEOUT);
        assertTrue(LlmRetry.isRetryable(e), "408 应可重试");
    }

    @Test
    void networkIOException_isRetryable() {
        // ResourceAccessException 包裹 SocketTimeoutException（IO 网络异常）→ 可重试
        RuntimeException e = new ResourceAccessException("I/O error on GET request",
                new java.net.ConnectException("Connection refused"));
        assertTrue(LlmRetry.isRetryable(e), "网络/连接异常应可重试");
    }

    @Test
    void badKey401_isNotRetryable() {
        RuntimeException e = new HttpClientErrorException(HttpStatus.UNAUTHORIZED);
        assertFalse(LlmRetry.isRetryable(e), "401 鉴权错误不应重试");
    }

    @Test
    void badParam400_isNotRetryable() {
        RuntimeException e = new HttpClientErrorException(HttpStatus.BAD_REQUEST);
        assertFalse(LlmRetry.isRetryable(e), "400 参数错误不应重试");
    }

    @Test
    void plainRuntime_isNotRetryable() {
        assertFalse(LlmRetry.isRetryable(new IllegalStateException("boom")),
                "非网络业务异常不应重试");
    }

    @Test
    void wrappedRetryableInCause_isDetected() {
        // Spring AI 外层再包一层 SpringAiException，内层 5xx → 应识别为可重试
        RuntimeException inner = new HttpServerErrorException(HttpStatus.BAD_GATEWAY);
        assertTrue(LlmRetry.isRetryable(new IllegalStateException("wrapped", inner)),
                "沿 cause 链剥壳应判定可重试");
    }

    // ---- 重试行为 ----

    @Test
    void firstAttemptFailsThenRetrySucceeds() {
        AtomicInteger calls = new AtomicInteger();
        String result = retry.executeWithRetry(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR);
            }
            return "ok";
        });
        assertEquals(2, calls.get(), "第一次失败后应重试并成功");
        assertEquals("ok", result);
    }

    @Test
    void retryExhausted_throwsLastError() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeException last = new HttpServerErrorException(HttpStatus.BAD_GATEWAY);
        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                retry.executeWithRetry(() -> {
                    calls.incrementAndGet();
                    throw last;
                }));
        assertEquals(3, calls.get(), "应尝试满 3 次");
        assertTrue(thrown == last, "重试耗尽应抛最后一次异常");
    }

    @Test
    void nonRetryable_throwsImmediately_noRetry() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(RuntimeException.class, () ->
                retry.executeWithRetry(() -> {
                    calls.incrementAndGet();
                    throw new HttpClientErrorException(HttpStatus.BAD_REQUEST);
                }));
        assertEquals(1, calls.get(), "不可重试错误应只调用 1 次");
    }
}