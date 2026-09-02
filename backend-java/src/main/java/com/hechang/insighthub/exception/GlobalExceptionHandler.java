package com.hechang.insighthub.exception;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
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
 * 统一异常处理：保留 BaseResponse 业务信封，同时返回与错误类型一致的 HTTP 状态。
 */
@Hidden
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<BaseResponse<?>> handleBusiness(BusinessException ex) {
        return ResponseEntity.status(httpStatus(ex.getCode()))
                .body(ResultUtils.error(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<BaseResponse<?>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getAllErrors().isEmpty()
                ? "validation failed"
                : ex.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        return ResponseEntity.badRequest().body(ResultUtils.error(ErrorCode.PARAMS_ERROR, msg));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<BaseResponse<?>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ResultUtils.error(ErrorCode.FORBIDDEN_ERROR));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<BaseResponse<?>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ResultUtils.error(ErrorCode.NOT_LOGIN_ERROR, "invalid credentials"));
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
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ResultUtils.error(ErrorCode.SYSTEM_ERROR, "unexpected error"));
    }

    @ExceptionHandler(Exception.class)
    public Object handleGeneric(Exception ex, HttpServletRequest request, HttpServletResponse response) {
        if (isSseResponse(request, response)) {
            log.debug("Ignore SSE exception after response is unavailable: {}", ex.getMessage());
            return ResponseEntity.noContent().build();
        }
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ResultUtils.error(ErrorCode.SYSTEM_ERROR, "unexpected error"));
    }

    private static HttpStatus httpStatus(int code) {
        if (code == ErrorCode.PARAMS_ERROR.getCode()) return HttpStatus.BAD_REQUEST;
        if (code == ErrorCode.NOT_LOGIN_ERROR.getCode() || code == ErrorCode.NO_AUTH_ERROR.getCode()) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (code == ErrorCode.FORBIDDEN_ERROR.getCode()) return HttpStatus.FORBIDDEN;
        if (code == ErrorCode.NOT_FOUND_ERROR.getCode()) return HttpStatus.NOT_FOUND;
        if (code == ErrorCode.CONFLICT_ERROR.getCode()) return HttpStatus.CONFLICT;
        if (code == ErrorCode.TOO_MANY_REQUEST.getCode()) return HttpStatus.TOO_MANY_REQUESTS;
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static boolean isSseResponse(HttpServletRequest request, HttpServletResponse response) {
        String contentType = response.getContentType();
        return (contentType != null && contentType.startsWith(MediaType.TEXT_EVENT_STREAM_VALUE))
                || request.getRequestURI().endsWith("/events");
    }
}
