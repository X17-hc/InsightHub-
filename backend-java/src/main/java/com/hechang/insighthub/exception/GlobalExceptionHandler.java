package com.hechang.insighthub.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
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

    @ExceptionHandler(Exception.class)
    public BaseResponse<?> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "unexpected error");
    }
}
