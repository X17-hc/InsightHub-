package com.hechang.insighthub.service.impl;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.annotation.Resource;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.hechang.insighthub.exception.BusinessException;
import com.hechang.insighthub.mapper.ResearchTaskMapper;
import com.hechang.insighthub.model.dto.task.AnalysisArtifactResponse;
import com.hechang.insighthub.model.entity.ResearchTask;
import com.hechang.insighthub.service.WorkspaceAccessService;

/** Java public-boundary proxy for Agent-owned artifact metadata and bytes. */
@Service
public class AnalysisArtifactService {
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final Set<String> ALLOWED_MIME = Set.of("text/csv", "application/json", "application/vnd.apache.parquet", "image/png", "image/svg+xml");
    @Resource private WorkspaceAccessService accessService;
    @Resource private ResearchTaskMapper researchTaskMapper;
    @Resource private WebClient agentWebClient;

    public List<AnalysisArtifactResponse> list(String workspaceId, String taskId) {
        requireTask(workspaceId, taskId);
        List<Map<String, Object>> rows = agentWebClient.get().uri(uri -> uri.path("/internal/v1/agent/tasks/{taskId}/artifacts")
                .queryParam("workspaceId", workspaceId).build(taskId)).retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {}).block();
        return (rows == null ? List.<Map<String, Object>>of() : rows).stream().map(AnalysisArtifactService::publicRow).toList();
    }

    public ArtifactContent content(String workspaceId, String taskId, String artifactId) {
        requireTask(workspaceId, taskId);
        return agentWebClient.get().uri(uri -> uri.path("/internal/v1/agent/tasks/{taskId}/artifacts/{artifactId}/content")
                .queryParam("workspaceId", workspaceId).build(taskId, artifactId)).exchangeToMono(response -> {
                    if (response.statusCode().value() == 404) return response.createException().flatMap(error -> reactor.core.publisher.Mono.error(BusinessException.notFound("artifact not found")));
                    MediaType type = response.headers().contentType().orElse(MediaType.APPLICATION_OCTET_STREAM);
                    if (!ALLOWED_MIME.contains(type.toString())) return reactor.core.publisher.Mono.error(BusinessException.badRequest("ARTIFACT_MIME_REJECTED", "artifact MIME type is not allowed"));
                    long length = response.headers().contentLength().orElse(-1L);
                    if (length > MAX_BYTES) return reactor.core.publisher.Mono.error(BusinessException.badRequest("ARTIFACT_TOO_LARGE", "artifact exceeds proxy limit"));
                    String name = response.headers().asHttpHeaders().getContentDisposition().getFilename();
                    return response.bodyToMono(byte[].class).map(bytes -> {
                        if (bytes.length > MAX_BYTES) throw BusinessException.badRequest("ARTIFACT_TOO_LARGE", "artifact exceeds proxy limit");
                        return new ArtifactContent(bytes, type, safeName(name));
                    });
                }).block();
    }

    private void requireTask(String workspaceId, String taskId) {
        accessService.requireCurrentMember(workspaceId);
        ResearchTask task = researchTaskMapper.findByIdAndWorkspace(taskId, workspaceId);
        if (task == null) throw BusinessException.notFound("task not found");
    }

    private static AnalysisArtifactResponse publicRow(Map<String, Object> row) {
        String mime = String.valueOf(row.getOrDefault("mimeType", ""));
        if (!ALLOWED_MIME.contains(mime)) throw BusinessException.badRequest("ARTIFACT_MIME_REJECTED", "artifact MIME type is not allowed");
        long size = row.get("size") instanceof Number n ? n.longValue() : 0L;
        return new AnalysisArtifactResponse(text(row, "id"), text(row, "taskId"), text(row, "workspaceId"), text(row, "runId"), text(row, "artifactType"), text(row, "title"), safeName(text(row, "fileName")), mime, size, text(row, "status"), text(row, "createdAt"));
    }
    private static String text(Map<String, Object> row, String key) { Object value = row.get(key); return value == null ? null : String.valueOf(value); }
    private static String safeName(String name) { return name == null || name.isBlank() ? "artifact" : name.replaceAll("[^\\p{L}\\p{N}._-]", "_"); }
    public record ArtifactContent(byte[] bytes, MediaType type, String filename) {}
}
