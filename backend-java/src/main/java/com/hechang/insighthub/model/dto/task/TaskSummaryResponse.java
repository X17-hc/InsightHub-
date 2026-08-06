package com.hechang.insighthub.model.dto.task;

import lombok.Data;

import java.sql.Timestamp;

/**
 * 任务摘要（列表/详情）。
 */
@Data
public class TaskSummaryResponse {

    private String taskId;
    private String workspaceId;
    private String creatorId;
    private String query;
    private String status;
    private int progress;
    private String traceId;
    private String runId;
    private String errorCode;
    private String errorMessage;
    private Timestamp createdAt;


}
