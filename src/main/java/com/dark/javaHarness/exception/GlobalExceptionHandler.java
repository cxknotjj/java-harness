package com.dark.javaHarness.exception;

import com.dark.javaHarness.domain.dto.ErrorResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：把异常统一转换为 {code, message} 响应体，
 * code 为 HTTP 状态码，message 为可读错误信息（不暴露内部细节）。
 *
 * <p>「客户端断连」（AsyncRequestNotUsableException / Connection reset / Broken pipe）
 * 是预期场景（CLI 超时/退出），降级为 warn 单行——不刷 ERROR 堆栈；响应体此时已无法送达客户端。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 断连特征消息片段（覆盖 Tomcat 包装的 ClientAbortException 等 IOException 形态） */
    private static final String[] ABORT_MESSAGE_HINTS = {
            "Connection reset", "connection reset", "Broken pipe", "broken pipe",
    };

    /** 业务/参数错误：参数非法、资源不存在等 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleIllegalArgument(IllegalArgumentException e) {
        return ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), e.getMessage());
    }

    /** 请求体 JSON 解析失败 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleUnreadable(HttpMessageNotReadableException e) {
        return ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), "请求体格式错误");
    }

    /** 缺少必填请求参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleMissingParam(MissingServletRequestParameterException e) {
        return ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), "缺少参数: " + e.getParameterName());
    }

    /** 参数校验失败（@Valid） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getDefaultMessage() == null ? "参数校验失败" : fe.getDefaultMessage())
                .orElse("参数校验失败");
        return ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), msg);
    }

    /** 404：请求的资源不存在 */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(NoResourceFoundException e) {
        return ErrorResponse.of(HttpStatus.NOT_FOUND.value(), "资源不存在");
    }

    /** 409：续跑冲突（goal 仍在执行中，防同一检查点被双跑） */
    @ExceptionHandler(ResumeConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleResumeConflict(ResumeConflictException e) {
        return ErrorResponse.of(HttpStatus.CONFLICT.value(), e.getMessage());
    }

    /** 502：模型供应商账户级硬错误（余额不足/配额耗尽）——上游故障透传，message 已是人话提示 */
    @ExceptionHandler(ModelQuotaException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ErrorResponse handleModelQuota(ModelQuotaException e) {
        log.warn("[quota] 模型供应商账户错误：{}", e.getMessage());
        return ErrorResponse.of(HttpStatus.BAD_GATEWAY.value(), e.getMessage());
    }

    /** 409：唯一键冲突（如 model_provider.model 大小写不敏感撞键，并发 add 场景兜底） */
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleDuplicateKey(org.springframework.dao.DuplicateKeyException e) {
        log.warn("[duplicate] 唯一键冲突：{}", e.getMessage());
        return ErrorResponse.of(HttpStatus.CONFLICT.value(), "记录已存在（模型名大小写不敏感），请勿重复添加");
    }

    /** 客户端断连（SSE/异步流式场景的预期事件）：warn 单行可观测，不刷 ERROR 堆栈 */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleClientDisconnect(AsyncRequestNotUsableException e) {
        log.warn("客户端断开，停止本次响应推送：{}", e.getMessage());
        return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR.value(), "客户端已断开");
    }

    /** 未捕获异常兜底 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleException(Exception e) {
        // 断连以 IOException 形态漏网（如 Tomcat ClientAbortException）同样降级 warn 单行
        if (isClientAbort(e)) {
            log.warn("客户端断开（{}），停止本次响应推送", e.getMessage());
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR.value(), "客户端已断开");
        }
        log.error("未处理异常", e);
        return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR.value(), "服务器内部错误");
    }

    /** 异常链中含断连特征的 IOException（不直接依赖 Tomcat 类，容器无关） */
    private static boolean isClientAbort(Throwable e) {
        for (Throwable cur = e; cur != null; cur = cur.getCause()) {
            if (cur instanceof IOException io && io.getMessage() != null) {
                for (String hint : ABORT_MESSAGE_HINTS) {
                    if (io.getMessage().contains(hint)) {
                        return true;
                    }
                }
            }
            if (cur.getCause() == cur) {
                break; // 自引用环防护
            }
        }
        return false;
    }
}
