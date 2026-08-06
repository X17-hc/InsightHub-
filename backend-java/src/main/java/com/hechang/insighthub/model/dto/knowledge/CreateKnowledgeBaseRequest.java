package com.hechang.insighthub.model.dto.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建知识库请求。
 */
@Data
public class CreateKnowledgeBaseRequest {

    @NotBlank
    @Size(max = 128)
    private String name;

    /** 可选描述 */
    @Size(max = 512)
    private String description;
}
