package com.hechang.insighthub.model.dto.task;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 与协议事件对齐的 DTO。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class AgentEventDto {

    private String schemaVersion = "1.0";
    private Long eventId;
    private String taskId;
    private String runId;
    private String node;
    private String type;
    private String timestamp;
    private Map<String, Object> data;

}
