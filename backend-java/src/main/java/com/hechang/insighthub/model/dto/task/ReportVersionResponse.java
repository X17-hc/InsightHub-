package com.hechang.insighthub.model.dto.task;

import java.time.LocalDateTime;

/** Immutable report version summary used by the history view. */
public record ReportVersionResponse(
        String id, int version, String title, String status, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
