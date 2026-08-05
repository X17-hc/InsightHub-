package com.insighthub.task.dto;

/**
 * 暂停 / 恢复 / 取消等控制响应。
 */
public class TaskControlResponse {

    private String taskId;
    private String status;

    public TaskControlResponse() {
    }

    public TaskControlResponse(String taskId, String status) {
        this.taskId = taskId;
        this.status = status;
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
}
