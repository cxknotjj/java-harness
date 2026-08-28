package com.dark.javaHarness.config.logging;

import ch.qos.logback.classic.LoggerContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * 日志降噪装配：向 logback 注册 {@link ClientAbortLogFilter}。
 *
 * <p>作用：客户端断开时 Tomcat/Spring MVC 刷出的 ERROR 级断连堆栈被 DENY，
 * 可观测性由业务层 doOnCancel 的 warn 单行承接（见 ChatServiceImpl / AgentServiceImpl）。
 */
@Configuration
public class LogNoiseConfig {

    /** logback 初始化早于 Spring 容器启动，@PostConstruct 时 LoggerContext 已就绪，可直接注册 */
    @PostConstruct
    public void registerTurboFilters() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx) {
            ctx.addTurboFilter(new ClientAbortLogFilter());
        }
    }
}
