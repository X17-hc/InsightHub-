package com.insighthub.task.dto;

/**
 * 异步创建任务 202 响应。
 */
public class CreateTaskAcceptedResponse {

    private String taskId;
    private String status;
    private String traceId;

    public CreateTaskAcceptedResponse() {
    }

    public CreateTaskAcceptedResponse(String taskId, String status, String traceId) {
        this.taskId = taskId;
        this.status = status;
        this.traceId = traceId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }
}
