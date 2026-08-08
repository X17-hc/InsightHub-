package com.hechang.insighthub.model.dto.task;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务事件（历史回放 / 详情页时间线）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskEventResponse {

    /** 任务内事件序号（对应 SSE id） */
    private long eventId;
    private String taskId;
    private String runId;
    private String node;
    private String type;
    private String timestamp;
    /** 事件载荷（原 payload_json） */
    private Map<String, Object> data;
}
