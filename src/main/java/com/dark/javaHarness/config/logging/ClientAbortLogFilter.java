package com.dark.javaHarness.config.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;

/**
 * 下游断连日志降噪过滤器（logback TurboFilter）。
 *
 * <p>背景：多 Agent 并发执行期间客户端断开（超时/退出）时，Tomcat/Spring MVC 异步管道会以
 * ERROR 级刷出 {@code AsyncRequestNotUsableException} / {@code ClientAbortException}
 * （Connection reset by peer）带全量堆栈——这是「客户端正常断开」的预期场景，不是故障：
 * 业务层（ChatServiceImpl.doOnCancel / AgentServiceImpl.doOnCancel）已统一记可观测 warn 单行。
 *
 * <p>策略：仅对框架包 logger 且异常/消息命中断连特征的 <b>ERROR</b> 事件 DENY（其余全放行）。
 * 限定框架 logger 前缀是为了不误杀业务代码里真实需要排查的连接类异常堆栈。
 */
public final class ClientAbortLogFilter extends TurboFilter {

    /** 仅降噪框架层 logger：Tomcat 连接器/容器 + Spring MVC 异步管道 */
    private static final String[] FRAMEWORK_LOGGER_PREFIXES = {
            "org.apache.catalina", "org.apache.coyote", "org.apache.tomcat",
            "org.springframework.web",
    };

    /** 断连特征异常类名（简单名包含匹配，覆盖 Tomcat/Spring 两种包装） */
    private static final String[] ABORT_EXCEPTION_NAMES = {
            "ClientAbortException", "AsyncRequestNotUsableException",
    };

    /** 断连特征消息片段（无异常对象时兜底匹配） */
    private static final String[] ABORT_MESSAGE_HINTS = {
            "Connection reset", "connection reset", "Broken pipe",
    };

    @Override
    public FilterReply decide(Marker marker, ch.qos.logback.classic.Logger logger,
                              Level level, String format, Object[] params, Throwable t) {
        if (level != Level.ERROR || logger == null) {
            return FilterReply.NEUTRAL;
        }
        if (!isFrameworkLogger(logger.getName()) || !isClientAbort(format, t)) {
            return FilterReply.NEUTRAL;
        }
        return FilterReply.DENY;
    }

    private static boolean isFrameworkLogger(String name) {
        for (String prefix : FRAMEWORK_LOGGER_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 异常链命中断连特征，或消息文本命中断连片段（无异常对象时） */
    private static boolean isClientAbort(String format, Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            for (String name : ABORT_EXCEPTION_NAMES) {
                if (cur.getClass().getSimpleName().contains(name)) {
                    return true;
                }
            }
            String msg = cur.getMessage();
            if (msg != null && isAbortMessage(msg)) {
                return true;
            }
            if (cur.getCause() == cur) {
                break; // 自引用环防护
            }
        }
        return format != null && isAbortMessage(format);
    }

    private static boolean isAbortMessage(String text) {
        for (String hint : ABORT_MESSAGE_HINTS) {
            if (text.contains(hint)) {
                return true;
            }
        }
        return false;
    }
}
