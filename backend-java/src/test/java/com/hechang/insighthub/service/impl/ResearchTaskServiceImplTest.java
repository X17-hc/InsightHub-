package com.hechang.insighthub.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hechang.insighthub.config.TaskProperties;
import com.hechang.insighthub.exception.BusinessException;
import com.hechang.insighthub.integration.AgentServiceClient;
import com.hechang.insighthub.mapper.CitationMapper;
import com.hechang.insighthub.mapper.KnowledgeBaseMapper;
import com.hechang.insighthub.mapper.ReportMapper;
import com.hechang.insighthub.mapper.ResearchTaskMapper;
import com.hechang.insighthub.mapper.TaskEventMapper;
import com.hechang.insighthub.model.dto.task.ReportResponse;
import com.hechang.insighthub.model.entity.Report;
import com.hechang.insighthub.model.entity.ResearchTask;
import com.hechang.insighthub.model.enums.TaskStatus;
import com.hechang.insighthub.redis.TaskControlRedis;
import com.hechang.insighthub.redis.TaskCreateRateLimiter;
import com.hechang.insighthub.redis.TaskSlotTracker;
import com.hechang.insighthub.redis.WorkspaceConcurrencyService;
import com.hechang.insighthub.service.TaskExecutionService;
import com.hechang.insighthub.service.WorkspaceAccessService;
import com.hechang.insighthub.security.UserPrincipal;

@ExtendWith(MockitoExtension.class)
class ResearchTaskServiceImplTest {

    @Mock
    private AgentServiceClient agentServiceClient;
    @Mock
    private TaskEventMapper taskEventMapper;
    @Mock
    private ReportMapper reportMapper;
    @Mock
    private CitationMapper citationMapper;
    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Mock
    private WorkspaceAccessService accessService;
    @Mock
    private TaskStateMachine stateMachine;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private TaskExecutionService taskExecutionService;
    @Mock
    private TaskControlRedis taskControlRedis;
    @Mock
    private WorkspaceConcurrencyService concurrencyService;
    @Mock
    private TaskSlotTracker slotTracker;
    @Mock
    private TaskCreateRateLimiter rateLimiter;
    @Mock
    private TaskEventSseHub sseHub;
    @Mock
    private TaskStreamLease streamLease;
    @Mock
    private TaskProperties taskProperties;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private ResearchTaskMapper researchTaskMapper;

    @InjectMocks
    private ResearchTaskServiceImpl service;

    @BeforeEach
    void setMapper() {
        ReflectionTestUtils.setField(service, "mapper", researchTaskMapper);
        UserPrincipal principal = new UserPrincipal("user-1", "tester", "", true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void advanceRejectsAConcurrentStateChange() {
        when(researchTaskMapper.updateStatusIfCurrent(
                "task-1", "workspace-1", "RUNNING", "GENERATING", 80, "write_report"))
                .thenReturn(0);

        assertThrows(
                BusinessException.class,
                () -> service.advance(
                        "task-1", "workspace-1",
                        TaskStatus.RUNNING, TaskStatus.GENERATING, 80, "write_report"));
    }

    @Test
    void getReportReturnsLatestReport() {
        ResearchTask task = new ResearchTask();
        task.setId("task-1");
        task.setWorkspaceId("workspace-1");
        when(researchTaskMapper.findByIdAndWorkspace("task-1", "workspace-1")).thenReturn(task);

        LocalDateTime now = LocalDateTime.now();
        Report report = new Report();
        report.setId("report-2");
        report.setTaskId("task-1");
        report.setWorkspaceId("workspace-1");
        report.setVersion(2);
        report.setTitle("Research result");
        report.setMarkdownContent("# Research result");
        report.setStatus("READY");
        report.setCreatedAt(now.minusMinutes(1));
        report.setUpdatedAt(now);
        when(reportMapper.findLatestByTaskAndWorkspace("task-1", "workspace-1")).thenReturn(report);

        ReportResponse response = service.getReport("workspace-1", "task-1");

        assertEquals("report-2", response.getId());
        assertEquals(2, response.getVersion());
        assertEquals("# Research result", response.getMarkdownContent());
        verify(accessService).requireMember("workspace-1", "user-1");
    }

    @Test
    void getReportReturnsNotFoundWhenNoReportExists() {
        ResearchTask task = new ResearchTask();
        task.setId("task-1");
        task.setWorkspaceId("workspace-1");
        when(researchTaskMapper.findByIdAndWorkspace("task-1", "workspace-1")).thenReturn(task);
        when(reportMapper.findLatestByTaskAndWorkspace("task-1", "workspace-1")).thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.getReport("workspace-1", "task-1"));

        assertEquals(40400, exception.getCode());
        assertEquals("report not found", exception.getMessage());
    }
}
