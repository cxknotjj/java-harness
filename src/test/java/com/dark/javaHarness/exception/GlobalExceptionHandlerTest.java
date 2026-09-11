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

    @Test
    void illegalState_surfacesReadableMessageAs503() {
        ErrorResponse resp = handler.handleIllegalState(
                new IllegalStateException("知识库向量库未装配（检查 app.knowledge.* 配置与 pgvector 依赖）"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), resp.code(), "未就绪类异常应映射 503");
        assertTrue(resp.message().contains("向量库未装配"), "可读指引应透出，不被吞成「服务器内部错误」");
        ILoggingEvent event = events.list.get(0);
        assertEquals(Level.WARN, event.getLevel(), "设计内异常降级为 warn");
    }

    @Test
    void illegalState_blankMessage_fallsBackToGeneric() {
        ErrorResponse resp = handler.handleIllegalState(new IllegalStateException((String) null));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), resp.code());
        assertEquals("服务未就绪", resp.message(), "message 为空时兜底通用文案");
    }

    @Test
    void modelAuth_mapsTo502WithActionableMessage() {
        ErrorResponse resp = handler.handleModelAuth(new ModelAuthException(
                "deepseek-v4-flash", 401, "模型 'deepseek-v4-flash' 调用失败（HTTP 401）：鉴权失败——请配置 key"));

        assertEquals(HttpStatus.BAD_GATEWAY.value(), resp.code(), "与配额类同口径映射 502");
        assertTrue(resp.message().contains("鉴权失败"), "人话指引透传给调用方");
    }
}
