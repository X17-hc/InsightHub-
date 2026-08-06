package com.hechang.insighthub.exception;

import lombok.Getter;

/**
 * 错误码枚举（对齐 code-ai-angent）。
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, "ok"),
    PARAMS_ERROR(40000, "请求参数错误"),
    NOT_LOGIN_ERROR(40100, "未登录"),
    NO_AUTH_ERROR(40101, "无权限"),
    NOT_FOUND_ERROR(40400, "请求数据不存在"),
    FORBIDDEN_ERROR(40300, "禁止访问"),
    TOO_MANY_REQUEST(42900, "请求过于频繁"),
    SYSTEM_ERROR(50000, "系统内部异常"),
    OPERATION_ERROR(50001, "操作失败"),
    /** 业务冲突（状态机/唯一约束等） */
    CONFLICT_ERROR(40900, "资源冲突");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
