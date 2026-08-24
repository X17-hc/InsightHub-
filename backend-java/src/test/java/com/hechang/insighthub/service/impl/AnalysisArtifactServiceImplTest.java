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
