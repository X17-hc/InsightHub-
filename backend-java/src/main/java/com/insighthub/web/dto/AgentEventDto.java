package com.insighthub.web.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 与协议事件对齐的 DTO。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentEventDto {

    private Long eventId;
    private String taskId;
    private String runId;
    private String node;
    private String type;
    private String timestamp;
    private Map<String, Object> data;

    public Long getEventId() {
        return eventId;
    }

    public void setEventId(Long eventId) {
        this.eventId = eventId;
    }

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

    public String getNode() {
        return node;
    }

    public void setNode(String node) {
        this.node = node;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }
}
