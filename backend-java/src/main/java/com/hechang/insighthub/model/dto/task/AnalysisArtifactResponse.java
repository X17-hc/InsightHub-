package com.hechang.insighthub.model.dto.task;

/** Public artifact metadata; storage locations are deliberately not exposed. */
public record AnalysisArtifactResponse(
        String id, String taskId, String workspaceId, String runId, String artifactType,
        String title, String fileName, String mimeType, long size, String status, String createdAt) {
}
