package com.dark.javaHarness.agent;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/**
 * LLM 调用重试策略：最多 {@code maxAttempts} 次，指数退避（含随机抖动）。
 *
 * <p>区分「可重试错误」与「不可重试错误」：
 * <ul>
 *   <li><b>可重试</b>：HTTP 5xx / 429（限流）/ 408（超时），以及网络/连接类异常
 *       （{@link ResourceAccessException} 或其 cause 为 {@link IOException} 的连接失败、读超时）。
 *       这类多为厂商端瞬时故障，重试大概率恢复。</li>
 *   <li><b>不可重试</b>：HTTP 4xx 中除 429 外的参数/鉴权错误（400/401/403/404 等）、
 *       以及非网络的业务异常（如工具执行错误）。重试只会重复失败。</li>
 * </ul>
 *
 * <p>退避：第 i 次重试前等待 {@code baseDelayMs * 2^i + 随机抖动(0..baseDelayMs)}，封顶 4s，
 * 避免多请求同时触发重试时反弹叠加。编排阶段调用为同步阻塞，故用 {@link Thread#sleep}。
 */
public final class LlmRetry {

    private static final Logger log = LoggerFactory.getLogger(LlmRetry.class);

    /** 默认最大尝试次数（=1 次原始调用 + 2 次重试） */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    /** 默认基准退避（毫秒） */
    private static final long DEFAULT_BASE_DELAY_MS = 500L;
    /** 单次退避封顶（毫秒） */
    private static final long MAX_BACKOFF_MS = 4000L;

    private final int maxAttempts;
    private final long baseDelayMs;

    public LlmRetry() {
        this(DEFAULT_MAX_ATTEMPTS, DEFAULT_BASE_DELAY_MS);
    }

    public LlmRetry(int maxAttempts, long baseDelayMs) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseDelayMs = Math.max(1, baseDelayMs);
    }

    /**
     * 可重试操作（单次 LLM 调用，不含重试与退避）。泛型返回：编排环节返回文本，
     * 路由判断返回 {@code RouteDecision} 等。
     */
    @FunctionalInterface
    public interface RetryOp<T> {
        T run();
    }

    /**
     * 判定异常是否可重试；沿着 cause 链剥壳（Spring AI 常把原始网络异常再包一层）。
     */
    public static boolean isRetryable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpStatusCodeException hse) {
                int code = hse.getStatusCode().value();
                if (code >= 500 || code == 429 || code == 408) {
                    return true;
                }
                if (code >= 400 && code < 500) {
                    return false;
                }
            }
            if (t instanceof ResourceAccessException) {
                Throwable cause = t.getCause();
                if (cause instanceof IOException || t.getMessage() == null) {
                    return true;
                }
            }
            if (t instanceof IOException) {
                return true;
            }
        }
        // 无法识别为明确客户端错误的，保守视为不可重试，避免无谓重试放大成本
        return false;
    }

    /**
     * 带重试地执行 {@code op}。可重试错误按指数退避重试，直到成功或尝试耗尽；
     * 不可重试错误立即抛出。重试耗尽时向上抛<b>最后一次</b>异常（供上层观测/兜底）。
     *
     * @return 成功时的调用结果
     * @throws RuntimeException 不可重试错误立即抛出，或可重试错误重试耗尽后抛出
     */
    public <T> T executeWithRetry(RetryOp<T> op)
            throws RuntimeException {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return op.run();
            } catch (RuntimeException e) {
                last = e;
                boolean retryable = isRetryable(e);
                if (!retryable) {
                    // 不可重试：直接抛，不透传重试上下文
                    throw e;
                }
                if (attempt == maxAttempts) {
                    log.warn("[llm-retry] 重试 {} 次后仍失败，放弃：{}", maxAttempts - 1, describe(e));
                    throw e;
                }
                long delay = backoffDelay(attempt);
                log.warn("[llm-retry] 可重试错误，{}ms 后第 {} 次重试（共 {} 次）：{}",
                        delay, attempt + 1, maxAttempts, describe(e));
                sleep(delay);
            }
        }
        throw last; // 理论不可达（maxAttempts>=1）
    }

    /** 本次计数可用的最大尝试次数（供外部循环判断是否还需重试） */
    public int maxAttempts() {
        return maxAttempts;
    }

    /** 指数退避 + 随机抖动，单次封顶 MAX_BACKOFF_MS（attempt 从 1 起） */
    private long backoffDelay(int attempt) {
        long exp = Math.min(MAX_BACKOFF_MS, baseDelayMs << (attempt - 1));
        return exp + (long) (baseDelayMs * Math.random());
    }

    /** 重试前等待（index 为「第几次重试前」，从 1 起）；供调用方在自管理循环里复用退避 */
    public void waitBeforeRetry(int attempt) {
        sleep(backoffDelay(attempt));
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("重试等待被打断", ie);
        }
    }

    private static String describe(Throwable e) {
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
    }
}