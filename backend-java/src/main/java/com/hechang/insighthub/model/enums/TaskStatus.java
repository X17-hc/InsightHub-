package com.hechang.insighthub.model.enums;

/**
 * 研究任务业务状态（设计文档 §8.1）。
 */
public enum TaskStatus {
    CREATED,
    PLANNING,
    WAITING_APPROVAL,
    RUNNING,
    PAUSING,
    PAUSED,
    REVIEWING,
    GENERATING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    public boolean matches(String value) {
        return name().equalsIgnoreCase(value);
    }

    public static boolean isTerminal(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            return valueOf(value.toUpperCase()).isTerminal();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public static TaskStatus parse(String value) {
        if (value == null || value.isBlank()) return null;
        return valueOf(value.toUpperCase());
    }

    public static TaskStatus tryParse(String value) {
        try {
            return parse(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
