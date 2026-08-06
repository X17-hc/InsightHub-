package com.hechang.insighthub.exception;

import lombok.Getter;

/**
 * 业务异常（对齐 code-ai-angent；附带迁移用静态工厂）。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    /** 参数错误 */
    public static BusinessException badRequest(String bizCode, String message) {
        return new BusinessException(ErrorCode.PARAMS_ERROR, bizCode + ": " + message);
    }

    /** 未登录 */
    public static BusinessException unauthorized(String message) {
        return new BusinessException(ErrorCode.NOT_LOGIN_ERROR, message);
    }

    /** 无权限 */
    public static BusinessException forbidden(String message) {
        return new BusinessException(ErrorCode.FORBIDDEN_ERROR, message);
    }

    /** 不存在 */
    public static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND_ERROR, message);
    }

    /** 冲突 */
    public static BusinessException conflict(String bizCode, String message) {
        return new BusinessException(ErrorCode.CONFLICT_ERROR, bizCode + ": " + message);
    }

    /** 限流 */
    public static BusinessException tooManyRequests(String bizCode, String message) {
        return new BusinessException(ErrorCode.TOO_MANY_REQUEST, bizCode + ": " + message);
    }
}
