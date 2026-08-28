package com.dark.javaHarness.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dark.javaHarness.domain.dto.ErrorResponse;
import java.io.IOException;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * GlobalExceptionHandler 断连降级单测：
 * - 断连（AsyncRequestNotUsable / ClientAbort 形态）→ warn 单行（无堆栈）
 * - 普通未处理异常仍保留 ERROR + 堆栈（不误杀）
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private ListAppender<ILoggingEvent> events;
    private ch.qos.logback.classic.Logger logbackLogger;

    @BeforeEach
    void captureLogs() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        logbackLogger = ctx.getLogger(GlobalExceptionHandler.class);
        events = new ListAppender<>();
        events.start();
        logbackLogger.addAppender(events);
    }

    @AfterEach
    void detachLogs() {
        logbackLogger.detachAppender(events);
    }

    @Test
    void asyncNotUsable_warnsSingleLineWithoutStack() {
        ErrorResponse resp = handler.handleClientDisconnect(
                new AsyncRequestNotUsableException("Connection reset by peer"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), resp.code());
        ILoggingEvent event = events.list.get(0);
        assertEquals(Level.WARN, event.getLevel(), "断连应降级为 warn");
        assertNull(event.getThrowableProxy(), "不应打印堆栈");
    }

    @Test
    void clientAbortIOException_inFallback_warnsSingleLine() {
        ErrorResponse resp = handler.handleException(
                new ClientAbortException(new IOException("Connection reset by peer")));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), resp.code());
        ILoggingEvent event = events.list.get(0);
        assertEquals(Level.WARN, event.getLevel(), "ClientAbort 形态断连应降级为 warn");
        assertNull(event.getThrowableProxy(), "不应打印堆栈");
    }

    @Test
    void unrelatedException_staysErrorWithStack() {
        ErrorResponse resp = handler.handleException(new IllegalStateException("boom"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), resp.code());
        assertEquals("服务器内部错误", resp.message());
        ILoggingEvent event = events.list.get(0);
        assertEquals(Level.ERROR, event.getLevel(), "非断连异常保持 ERROR");
        assertTrue(event.getThrowableProxy() != null, "保留堆栈供排查");
    }
}
