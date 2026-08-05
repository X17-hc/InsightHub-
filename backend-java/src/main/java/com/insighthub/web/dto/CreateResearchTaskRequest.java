package com.insighthub.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 对外创建研究任务请求。
 */
@Data
public class CreateResearchTaskRequest {

    @NotBlank
    private String query;

}
