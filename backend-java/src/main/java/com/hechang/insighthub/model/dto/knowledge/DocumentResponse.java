package com.hechang.insighthub.model.dto.knowledge;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库文档响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentResponse {

    private String id;
    private String knowledgeBaseId;
    private String workspaceId;
    private String fileName;
    private String contentType;
    private Long fileSize;
    private String contentHash;
    private String sourceUri;
    private String parseStatus;
    private int chunkCount;
    private String errorMessage;
    private String uploadedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
