package com.hechang.insighthub.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hechang.insighthub.config.TaskProperties;
import com.hechang.insighthub.exception.BusinessException;
import com.hechang.insighthub.integration.AgentServiceClient;
import com.hechang.insighthub.mapper.CitationMapper;
import com.hechang.insighthub.mapper.KnowledgeBaseMapper;
import com.hechang.insighthub.mapper.ReportMapper;
import com.hechang.insighthub.mapper.ResearchTaskMapper;
import com.hechang.insighthub.mapper.TaskEventMapper;
import com.hechang.insighthub.model.enums.TaskStatus;
import com.hechang.insighthub.redis.TaskControlRedis;
import com.hechang.insighthub.redis.TaskCreateRateLimiter;
import com.hechang.insighthub.redis.TaskSlotTracker;
import com.hechang.insighthub.redis.WorkspaceConcurrencyService;
import com.hechang.insighthub.service.TaskExecutionService;
import com.hechang.insighthub.service.WorkspaceAccessService;

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
}
