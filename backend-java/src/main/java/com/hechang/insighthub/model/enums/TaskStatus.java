package com.hechang.insighthub.model.enums;

/**
 * 研究任务业务状态（设计文档 §8.1）。
 */
public enum TaskStatus {
    CREATED,
    PLANNING,
    WAITING_APPROVAL,
    RUNNING,
    PAUSED,
    REVIEWING,
    GENERATING,
    COMPLETED,
    FAILED,
    CANCELLED
}
