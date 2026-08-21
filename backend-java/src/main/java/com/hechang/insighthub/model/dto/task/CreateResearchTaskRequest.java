package com.hechang.insighthub.model.dto.task;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 对外创建研究任务请求。
 */
@Data
public class CreateResearchTaskRequest {

    @NotBlank
    private String query;

    /** 可选：绑定本工作空间内的知识库 ID 列表 */
    private List<String> knowledgeBaseIds = new ArrayList<>();

    /** 仅用户显式启用时才运行数据分析 Sandbox。 */
    private boolean enableDataAnalysis;
}
