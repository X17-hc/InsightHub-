package com.hechang.insighthub.service.impl;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.hechang.insighthub.exception.BusinessException;
import com.hechang.insighthub.exception.ErrorCode;
import com.hechang.insighthub.mapper.ResearchTaskMapper;
import com.hechang.insighthub.model.dto.task.AnalysisArtifactResponse;
import com.hechang.insighthub.model.entity.ResearchTask;
import com.hechang.insighthub.service.AnalysisArtifactService;
import com.hechang.insighthub.service.WorkspaceAccessService;
import lombok.RequiredArgsConstructor;

/** Java public-boundary proxy for Agent-owned artifact metadata and bytes. */
@Service
@RequiredArgsConstructor
public class AnalysisArtifactServiceImpl implements AnalysisArtifactService {
    private static final Logger log = LoggerFactory.getLogger(AnalysisArtifactServiceImpl.class);
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final Set<String> ALLOWED_MIME = Set.of("text/csv", "application/json", "application/vnd.apache.parquet", "image/png", "image/svg+xml");
    private final WorkspaceAccessService accessService;
    private final ResearchTaskMapper researchTaskMapper;
    private final WebClient agentWebClient;

    public List<AnalysisArtifactResponse> list(String workspaceId, String taskId) {
        requireTask(workspaceId, taskId);
        try {
            List<Map<String, Object>> rows = agentWebClient.get().uri(uri -> uri.path("/internal/v1/agent/tasks/{taskId}/artifacts")
                    .queryParam("workspaceId", workspaceId).build(taskId)).retrieve()
                    .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {}).block();
            return (rows == null ? List.<Map<String, Object>>of() : rows).stream()
                    .map(AnalysisArtifactServiceImpl::publicRow)
                    .toList();
        } catch (WebClientRequestException exception) {
            throw unavailable("list", workspaceId, taskId, exception);
        } catch (WebClientResponseException exception) {
            throw upstreamFailure("list", workspaceId, taskId, exception);
        }
    }

    public ArtifactContent content(String workspaceId, String taskId, String artifactId) {
        requireTask(workspaceId, taskId);
        ArtifactHeaders headers;
        try {
            headers = agentWebClient.head().uri(uri -> artifactUri(uri, taskId, artifactId, workspaceId))
                    .exchangeToMono(response -> {
                        if (response.statusCode().value() == 404) {
                            return response.createException().flatMap(error -> reactor.core.publisher.Mono.error(BusinessException.notFound("artifact not found")));
                        }
                        if (!response.statusCode().is2xxSuccessful()) {
                            return response.createException().flatMap(reactor.core.publisher.Mono::error);
                        }
                        MediaType type = response.headers().contentType().orElse(MediaType.APPLICATION_OCTET_STREAM);
                        if (!ALLOWED_MIME.contains(type.toString())) {
                            return reactor.core.publisher.Mono.error(BusinessException.badRequest("ARTIFACT_MIME_REJECTED", "artifact MIME type is not allowed"));
                        }
                        long length = response.headers().contentLength().orElse(-1L);
                        if (length < 0 || length > MAX_BYTES) {
                            return reactor.core.publisher.Mono.error(BusinessException.badRequest("ARTIFACT_TOO_LARGE", "artifact exceeds proxy limit"));
                        }
                        String name = response.headers().asHttpHeaders().getContentDisposition().getFilename();
                        return reactor.core.publisher.Mono.just(new ArtifactHeaders(type, safeName(name), length));
                    }).block();
        } catch (WebClientRequestException exception) {
            throw unavailable("content-head", workspaceId, taskId, exception);
        } catch (WebClientResponseException exception) {
            throw upstreamFailure("content-head", workspaceId, taskId, exception);
        }
        if (headers == null) throw BusinessException.notFound("artifact not found");
        return new ArtifactContent(headers.type(), headers.filename(), headers.length(), output -> streamTo(output, taskId, artifactId, workspaceId));
    }

    private void streamTo(java.io.OutputStream output, String taskId, String artifactId, String workspaceId) {
        AtomicLong transferred = new AtomicLong();
        try {
            agentWebClient.get().uri(uri -> artifactUri(uri, taskId, artifactId, workspaceId)).exchangeToFlux(response -> {
                if (!response.statusCode().is2xxSuccessful()) return response.createException().flatMapMany(reactor.core.publisher.Flux::error);
                return response.bodyToFlux(DataBuffer.class);
            }).doOnNext(buffer -> {
                try {
                    int readable = buffer.readableByteCount();
                    if (transferred.addAndGet(readable) > MAX_BYTES) {
                        throw BusinessException.badRequest("ARTIFACT_TOO_LARGE", "artifact exceeds proxy limit");
                    }
                    byte[] chunk = new byte[readable];
                    buffer.read(chunk);
                    output.write(chunk);
                } catch (java.io.IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                } finally {
                    DataBufferUtils.release(buffer);
                }
            }).then().block();
        } catch (WebClientRequestException exception) {
            throw unavailable("content-stream", workspaceId, taskId, exception);
        } catch (WebClientResponseException exception) {
            throw upstreamFailure("content-stream", workspaceId, taskId, exception);
        }
    }

    private static java.net.URI artifactUri(
            org.springframework.web.util.UriBuilder uri, String taskId, String artifactId, String workspaceId) {
        return uri.path("/internal/v1/agent/tasks/{taskId}/artifacts/{artifactId}/content")
                .queryParam("workspaceId", workspaceId).build(taskId, artifactId);
    }

    private void requireTask(String workspaceId, String taskId) {
        accessService.requireCurrentMember(workspaceId);
        ResearchTask task = researchTaskMapper.findByIdAndWorkspace(taskId, workspaceId);
        if (task == null) throw BusinessException.notFound("task not found");
    }

    private BusinessException unavailable(
            String operation, String workspaceId, String taskId, WebClientRequestException exception) {
        log.warn(
                "Agent artifact request unavailable operation={} workspaceId={} taskId={} cause={}",
                operation,
                workspaceId,
                taskId,
                exception.getClass().getSimpleName());
        return new BusinessException(
                ErrorCode.OPERATION_ERROR,
                "AGENT_UNAVAILABLE: analysis artifact service is temporarily unavailable");
    }

    private BusinessException upstreamFailure(
            String operation, String workspaceId, String taskId, WebClientResponseException exception) {
        int status = exception.getStatusCode().value();
        log.warn(
                "Agent artifact request rejected operation={} workspaceId={} taskId={} status={}",
                operation,
                workspaceId,
                taskId,
                status);
        if (status == 401 || status == 403) {
            return new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "AGENT_AUTH_FAILED: internal Agent authentication failed");
        }
        return new BusinessException(
                ErrorCode.OPERATION_ERROR,
                "AGENT_UNAVAILABLE: analysis artifact service is temporarily unavailable");
    }

    private static AnalysisArtifactResponse publicRow(Map<String, Object> row) {
        String mime = String.valueOf(row.getOrDefault("mimeType", ""));
        if (!ALLOWED_MIME.contains(mime)) throw BusinessException.badRequest("ARTIFACT_MIME_REJECTED", "artifact MIME type is not allowed");
        long size = row.get("size") instanceof Number n ? n.longValue() : 0L;
        return new AnalysisArtifactResponse(text(row, "id"), text(row, "taskId"), text(row, "workspaceId"), text(row, "runId"), text(row, "artifactType"), text(row, "title"), safeName(text(row, "fileName")), mime, size, text(row, "status"), text(row, "createdAt"));
    }
    private static String text(Map<String, Object> row, String key) { Object value = row.get(key); return value == null ? null : String.valueOf(value); }
    private static String safeName(String name) { return name == null || name.isBlank() ? "artifact" : name.replaceAll("[^\\p{L}\\p{N}._-]", "_"); }
    private record ArtifactHeaders(MediaType type, String filename, long length) {}
}
