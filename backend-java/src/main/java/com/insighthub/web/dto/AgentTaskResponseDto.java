package com.insighthub.web.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Python Agent 同步响应 / Java 对外响应。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentTaskResponseDto {

    private String taskId;
    private String runId;
    private String traceId;
    private String status;
    private String reportMarkdown;
    private List<AgentEventDto> events = new ArrayList<>();
    private Map<String, Object> error;

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReportMarkdown() {
        return reportMarkdown;
    }

    public void setReportMarkdown(String reportMarkdown) {
        this.reportMarkdown = reportMarkdown;
    }

    public List<AgentEventDto> getEvents() {
        return events;
    }

    public void setEvents(List<AgentEventDto> events) {
        this.events = events;
    }

    public Map<String, Object> getError() {
        return error;
    }

    public void setError(Map<String, Object> error) {
        this.error = error;
    }
}
