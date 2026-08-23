package com.hechang.insighthub.service;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.hechang.insighthub.model.dto.task.AnalysisArtifactResponse;

/** Authorized public-boundary access to Agent-owned analysis artifacts. */
public interface AnalysisArtifactService {

    List<AnalysisArtifactResponse> list(String workspaceId, String taskId);

    ArtifactContent content(String workspaceId, String taskId, String artifactId);

    /**
     * Metadata is checked before the HTTP response is committed; the body is
     * streamed afterwards so a permitted artifact is never buffered in Java.
     */
    record ArtifactContent(MediaType type, String filename, long contentLength,
                           StreamingResponseBody body) {}
}
