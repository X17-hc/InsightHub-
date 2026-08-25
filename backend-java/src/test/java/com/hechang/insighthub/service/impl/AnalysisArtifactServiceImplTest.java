package com.hechang.insighthub.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import com.hechang.insighthub.exception.BusinessException;
import com.hechang.insighthub.exception.ErrorCode;
import com.hechang.insighthub.mapper.ResearchTaskMapper;
import com.hechang.insighthub.model.entity.ResearchTask;
import com.hechang.insighthub.service.WorkspaceAccessService;

import reactor.core.publisher.Mono;

class AnalysisArtifactServiceImplTest {

    @Test
    void preservesPreviewMetadataFromAgentList() {
        WorkspaceAccessService accessService = mock(WorkspaceAccessService.class);
        ResearchTaskMapper taskMapper = mock(ResearchTaskMapper.class);
        when(taskMapper.findByIdAndWorkspace("task-1", "workspace-1"))
                .thenReturn(new ResearchTask());
        String body = """
                [{"id":"artifact-1","taskId":"task-1","workspaceId":"workspace-1",
                  "runId":"run-1","artifactType":"CHART","title":"evidence_by_source",
                  "fileName":"evidence_by_source.png","mimeType":"image/png","size":17407,
                  "status":"SUCCESS","createdAt":"2026-08-25T10:08:25+08:00"}]
                """;
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(body)
                        .build()))
                .build();
        AnalysisArtifactServiceImpl service = new AnalysisArtifactServiceImpl(
                accessService,
                taskMapper,
                webClient);

        var artifacts = service.list("workspace-1", "task-1");

        assertEquals(1, artifacts.size());
        assertEquals("evidence_by_source", artifacts.getFirst().title());
        assertEquals("evidence_by_source.png", artifacts.getFirst().fileName());
        assertEquals("image/png", artifacts.getFirst().mimeType());
    }

    @Test
    void mapsConnectionRefusalToStableBusinessError() {
        WorkspaceAccessService accessService = mock(WorkspaceAccessService.class);
        ResearchTaskMapper taskMapper = mock(ResearchTaskMapper.class);
        when(taskMapper.findByIdAndWorkspace("task-1", "workspace-1"))
                .thenReturn(new ResearchTask());

        WebClientRequestException connectionFailure = new WebClientRequestException(
                new ConnectException("connection refused"),
                HttpMethod.GET,
                URI.create("http://agent.invalid/internal/v1/agent/tasks/task-1/artifacts"),
                HttpHeaders.EMPTY);
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.error(connectionFailure))
                .build();
        AnalysisArtifactServiceImpl service = new AnalysisArtifactServiceImpl(
                accessService,
                taskMapper,
                webClient);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.list("workspace-1", "task-1"));

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
        assertTrue(exception.getMessage().startsWith("AGENT_UNAVAILABLE:"));
    }
}
