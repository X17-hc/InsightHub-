package com.insighthub.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 对外创建研究任务请求。
 */
public class CreateResearchTaskRequest {

    @NotBlank
    private String query;

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }
}
