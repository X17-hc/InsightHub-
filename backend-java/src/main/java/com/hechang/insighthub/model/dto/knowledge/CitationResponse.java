package com.hechang.insighthub.model.dto.knowledge;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 报告引用响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CitationResponse {

    private String id;
    private String reportId;
    private String taskId;
    private Integer citationNo;
    private String sourceTitle;
    private String sourceUri;
    private String sourceType;
    private String documentId;
    private String chunkId;
    private String quotedText;
    private Integer verified;
    private String verificationStatus;
    private String verificationReason;
    private String canonicalUri;
    private String finalUri;
    private LocalDateTime retrievedAt;
    private String contentHash;
    private Integer httpStatus;
    private LocalDateTime createdAt;
}
