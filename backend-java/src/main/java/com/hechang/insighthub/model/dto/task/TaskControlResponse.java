package com.hechang.insighthub.model.dto.task;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 暂停 / 恢复 / 取消等控制响应。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TaskControlResponse {

    private String taskId;
    private String status;


}
