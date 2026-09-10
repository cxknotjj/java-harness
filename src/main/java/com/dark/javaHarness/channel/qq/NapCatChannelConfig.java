package com.dark.javaHarness.channel.qq;

import com.dark.javaHarness.mapper.OneBotSessionBindingMapper;
import com.dark.javaHarness.service.ChatService;
import com.dark.javaHarness.service.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * QQ 渠道装配（napcat.enabled=true 时生效）：onebotExecutor 线程池 + 上报端点 +
 * 事件处理 + NapCat 客户端整链。
 *
 * <p>垂直包隔离（channel/qq 单向依赖）：对 core 只依赖 {@link ChatService} /
 * {@link SessionService} 两个端口与 mapper/entity（共享位置惯例），core 反向
 * import channel 禁止。整链 bean 全部在本类 @Bean 装配（类上不标 @Component），
 * {@code napcat.enabled=false} 时端点、线程池、出站客户端一并不存在。
 *
 * <p>onebotExecutor 照 GoalExecutorConfig 先例：有界队列 + Abort 拒绝——QQ 消息
 * 是分钟内可弃的低价值任务，拒绝（该条不回复）好过无声排队积压。
 */
@Configuration
@ConditionalOnProperty(prefix = "napcat", name = "enabled", havingValue = "true")
public class NapCatChannelConfig {

    /** QQ 个人频道并发：LLM 调用为主耗时，4 线程足够，过大撞下游模型限流 */
    private static final int POOL_SIZE = 4;

    private static final int QUEUE_CAPACITY = 50;

    @Bean("onebotExecutor")
    public ThreadPoolTaskExecutor onebotExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(POOL_SIZE);
        executor.setMaxPoolSize(POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("onebot-exec-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationMillis(10_000);
        return executor;
    }

    @Bean
    public NapCatApiClient napCatApiClient(NapCatProperties props, ObjectMapper objectMapper) {
        return new NapCatApiClientImpl(props, objectMapper);
    }

    @Bean
    public OneBotEventService oneBotEventService(ChatService chatService,
                                                 SessionService sessionService,
                                                 OneBotSessionBindingMapper bindingMapper,
                                                 NapCatApiClient apiClient,
                                                 NapCatProperties props) {
        return new OneBotEventServiceImpl(chatService, sessionService, bindingMapper, apiClient, props);
    }

    @Bean
    public OneBotEventController oneBotEventController(ObjectMapper objectMapper,
                                                       OneBotEventService eventService,
                                                       @Qualifier("onebotExecutor") Executor onebotExecutor,
                                                       NapCatProperties props) {
        return new OneBotEventController(objectMapper, eventService, onebotExecutor, props);
    }
}
