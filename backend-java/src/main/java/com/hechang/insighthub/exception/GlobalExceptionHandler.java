package com.hechang.insighthub.exception;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.hechang.insighthub.common.BaseResponse;
import com.hechang.insighthub.common.ResultUtils;

import io.swagger.v3.oas.annotations.Hidden;

/**
 * 统一异常处理：返回 BaseResponse 信封（HTTP 200 + 业务 code）。
 */
@Hidden
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> handleBusiness(BusinessException ex) {
        return ResultUtils.error(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public BaseResponse<?> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getAllErrors().isEmpty()
                ? "validation failed"
                : ex.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, msg);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public BaseResponse<?> handleAccessDenied(AccessDeniedException ex) {
        return ResultUtils.error(ErrorCode.FORBIDDEN_ERROR);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public BaseResponse<?> handleBadCredentials(BadCredentialsException ex) {
        return ResultUtils.error(ErrorCode.NOT_LOGIN_ERROR, "invalid credentials");
    }

    /**
     * SSE 客户端已关闭时，Servlet 响应不能再写入内容。这不是业务失败，也不能套用 JSON 错误信封。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException ex) {
        log.debug("Ignore unavailable async response: {}", ex.getMessage());
    }

    /**
     * Tomcat 在对端关闭 SSE socket 时可能直接抛出 IOException，而非包装为
     * AsyncRequestNotUsableException。事件流已经开始后不能再写 JSON 错误体。
     */
    @ExceptionHandler(IOException.class)
    public Object handleIo(IOException ex, HttpServletRequest request, HttpServletResponse response) {
        if (isSseResponse(request, response)) {
            log.debug("Ignore SSE client disconnect: {}", ex.getMessage());
            return ResponseEntity.noContent().build();
        }
        log.error("Unhandled I/O exception", ex);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "unexpected error");
    }

    @ExceptionHandler(Exception.class)
    public Object handleGeneric(Exception ex, HttpServletRequest request, HttpServletResponse response) {
        if (isSseResponse(request, response)) {
            log.debug("Ignore SSE exception after response is unavailable: {}", ex.getMessage());
            return ResponseEntity.noContent().build();
        }
        log.error("Unhandled exception", ex);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "unexpected error");
    }

    private static boolean isSseResponse(HttpServletRequest request, HttpServletResponse response) {
        String contentType = response.getContentType();
        return (contentType != null && contentType.startsWith(MediaType.TEXT_EVENT_STREAM_VALUE))
                || request.getRequestURI().endsWith("/events");
    }
}
