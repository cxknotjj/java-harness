package com.dark.javaHarness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * SandboxToolProvider 初始化超时兜底单测：
 * - init 挂死（模拟 Docker 命名管道无超时阻塞）时，baseTools() 必须在超时上限内返回空工具面，
 *   而不是永久阻塞请求线程（线上实测：无 Docker 时管道连接挂死 3 分钟以上）
 * - 全进程只初始化一次：超时后再次取用不再等待、不再重试
 * - init 正常完成时工具面照常可用
 */
class SandboxToolProviderTest {

    /** init 挂死场景：超时后必须放行，且只尝试一次 */
    @Test
    void hangsInitDoesNotBlockCallerBeyondTimeout() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        SandboxToolProvider provider = new SandboxToolProvider() {
            @Override
            protected void init() {
                attempts.incrementAndGet();
                try {
                    Thread.sleep(60_000); // 模拟命名管道永久阻塞
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    entered.countDown();
                }
            }
        };
        provider.initTimeoutMs = 300;

        long start = System.nanoTime();
        assertTrue(provider.baseTools().isEmpty(), "超时后应返回空工具面");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 5_000, "首次取用应在超时上限附近返回，实际 " + elapsedMs + "ms");

        assertTrue(entered.await(2, TimeUnit.SECONDS), "init 应已在后台线程执行");
        assertTrue(provider.readOnlyFileTools().isEmpty());
        assertTrue(provider.writeTools().isEmpty());
        assertEquals(1, attempts.get(), "超时后不应重试，全进程只尝试一次");
    }

    /** init 正常完成：工具面可用，且不会二次初始化 */
    @Test
    void successInitExposesToolsOnce() {
        AtomicInteger attempts = new AtomicInteger();
        SandboxToolProvider provider = new SandboxToolProvider() {
            @Override
            protected void init() {
                attempts.incrementAndGet();
                this.base = java.util.List.of(); // 无真实 Docker，仅验证放行与一次性
                this.readOnly = java.util.List.of();
                this.write = java.util.List.of();
            }
        };
        provider.initTimeoutMs = 2_000;

        provider.baseTools();
        provider.baseTools();
        provider.readOnlyFileTools();

        assertEquals(1, attempts.get(), "初始化应恰好执行一次");
    }

    /** init 抛异常：不向上传播，工具面为空 */
    @Test
    void failedInitReturnsEmptyFace() {
        SandboxToolProvider provider = new SandboxToolProvider() {
            @Override
            protected void init() {
                throw new IllegalStateException("docker not available");
            }
        };
        provider.initTimeoutMs = 2_000;

        assertTrue(provider.baseTools().isEmpty(), "初始化失败应返回空工具面而非抛出");
    }
}
