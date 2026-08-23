package com.hechang.insighthub.model.dto.task;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Latest generated report for a research task. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReportResponse {

    private String id;
    private String taskId;
    private String workspaceId;
    private Integer version;
    private String title;
    private String markdownContent;
    private String status;
    private String qualityStatus;
    private String qualitySummary;
    private Integer verifiedCitationCount;
    private Integer candidateCitationCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
