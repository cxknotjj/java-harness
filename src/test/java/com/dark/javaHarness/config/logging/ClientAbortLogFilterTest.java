package com.dark.javaHarness.config.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.spi.FilterReply;
import java.io.IOException;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * ClientAbortLogFilter 降噪判定单测：
 * - 框架 logger + 断连特征（异常类/消息）的 ERROR → DENY
 * - 业务 logger、非 ERROR、非断连事件 → NEUTRAL（放行，不误杀）
 */
class ClientAbortLogFilterTest {

    private final ClientAbortLogFilter filter = new ClientAbortLogFilter();

    /** 用真实 LoggerContext 取 logback logger（TurboFilter 只读 name，安全） */
    private FilterReply decide(String loggerName, Level level, String format, Throwable t) {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        return filter.decide(null, ctx.getLogger(loggerName), level, format, null, t);
    }

    @Test
    void frameworkErrorWithClientAbort_isDenied() {
        assertEquals(FilterReply.DENY, decide("org.apache.catalina.core.StandardWrapperValve",
                Level.ERROR, "Servlet.service() threw exception",
                new ClientAbortException(new IOException("Connection reset by peer"))));
    }

    @Test
    void frameworkErrorWithAsyncNotUsable_isDenied() {
        assertEquals(FilterReply.DENY, decide("org.springframework.web.context.request.async.WebAsyncManager",
                Level.ERROR, "Async request not usable",
                new AsyncRequestNotUsableException("Connection reset by peer",
                        new IOException("Connection reset by peer"))));
    }

    @Test
    void frameworkErrorWithAbortMessageOnly_isDenied() {
        // 无异常对象、仅消息命中（log.error("... Broken pipe") 形态）
        assertEquals(FilterReply.DENY, decide("org.apache.coyote.http11.Http11Processor",
                Level.ERROR, "Error reading request: Broken pipe", null));
    }

    @Test
    void nestedCauseAbort_isDenied() {
        // 断连异常被业务异常包装在 cause 里仍应命中
        assertEquals(FilterReply.DENY, decide("org.springframework.web.servlet.DispatcherServlet",
                Level.ERROR, "Failed to complete request",
                new RuntimeException("dispatch failed",
                        new ClientAbortException(new IOException("connection reset")))));
    }

    @Test
    void businessLogger_isNeutral() {
        // 业务代码里的连接类异常不降噪：保留全量堆栈供排查
        assertEquals(FilterReply.NEUTRAL, decide("com.dark.javaHarness.service.impl.ChatServiceImpl",
                Level.ERROR, "chat failed",
                new ClientAbortException(new IOException("Connection reset"))));
    }

    @Test
    void warnLevel_isNeutral() {
        // 降噪仅针对 ERROR：业务/框架的 warn 可观测行必须放行
        assertEquals(FilterReply.NEUTRAL, decide("org.apache.catalina.connector.CoyoteAdapter",
                Level.WARN, "Connection reset by peer", null));
    }

    @Test
    void unrelatedError_isNeutral() {
        assertEquals(FilterReply.NEUTRAL, decide("org.apache.catalina.core.StandardWrapperValve",
                Level.ERROR, "Servlet.service() threw exception",
                new IllegalStateException("bad state")));
    }
}
