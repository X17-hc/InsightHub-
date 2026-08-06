package com.hechang.insighthub.model.dto.knowledge;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBaseResponse {

    private String id;
    private String workspaceId;
    private String name;
    private String description;
    private String embeddingModel;
    private String chunkStrategy;
    private String status;
    private int docCount;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
