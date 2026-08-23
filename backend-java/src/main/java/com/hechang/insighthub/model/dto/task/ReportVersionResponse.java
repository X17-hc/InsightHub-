package com.hechang.insighthub.model.dto.task;

import java.time.LocalDateTime;

/** Immutable report version summary used by the history view. */
public record ReportVersionResponse(
        String id, int version, String title, String status, String qualityStatus,
        String qualitySummary,
        int verifiedCitationCount, int candidateCitationCount,
        LocalDateTime createdAt, LocalDateTime updatedAt) {
}
