package com.dark.javaHarness.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Goal 异步执行线程池：承接 AgentServiceImpl#submit 的后台目标执行，
 * 替代原先占用的 ForkJoinPool.commonPool()——goal 执行体是分钟级阻塞 LLM 调用，
 * 占满 commonPool（CPU-1 线程，JVM 全局共享）会拖累并行流等无关共用方。
 *
 * <p>每个在途 goal 独占一个线程阻塞等待 LLM I/O，线程数即最大并发编排数，
 * 过大撞下游模型限流，过小拉长排队；队列缓存超出并发的提交，超出队列容量的
 * 提交按默认 Abort 策略拒绝，由 submit 捕获后把 Goal 落为 FAILED 终态
 * （客户端轮询可见），不无声排队。
 *
 * <p>本 bean 定义后 Boot 自动配置的 applicationTaskExecutor 按
 * {@code @ConditionalOnMissingBean(Executor.class)} 让位——项目无 @Async 消费方，无影响。
 */
@Configuration
public class GoalExecutorConfig {

    /** 最大并发执行数：每线程阻塞于 LLM I/O，按下游限流可调 */
    private static final int POOL_SIZE = 8;

    /** 等待队列容量：并发满后的缓冲深度，超出即拒绝 */
    private static final int QUEUE_CAPACITY = 50;

    @Bean("goalExecutor")
    public ThreadPoolTaskExecutor goalExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(POOL_SIZE);
        executor.setMaxPoolSize(POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("goal-exec-");
        // 优雅停机：在途 goal 等待完成再关；等待超时被中断的部分由启动清理（failAllRunning）兜底标记
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationMillis(30_000);
        return executor;
    }
}
