package com.insighthub.web.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Python Agent 同步响应 / Java 对外响应。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class AgentTaskResponseDto {

    private String taskId;
    private String runId;
    private String traceId;
    private String status;
    private String reportMarkdown;
    private List<AgentEventDto> events = new ArrayList<>();
    private Map<String, Object> error;

}
