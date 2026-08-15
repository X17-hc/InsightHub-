package com.hechang.insighthub.model.dto.task;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 异步创建任务 202 响应。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateTaskAcceptedResponse {

    private String taskId;
    private String status;
    private String traceId;

}
