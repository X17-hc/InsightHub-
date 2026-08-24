package com.hechang.insighthub.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hechang.insighthub.config.TaskProperties;
import com.hechang.insighthub.mapper.ResearchTaskMapper;
import com.hechang.insighthub.mapper.TaskDispatchOutboxMapper;
import com.hechang.insighthub.mapper.TaskPlanRevisionMapper;
import com.hechang.insighthub.model.entity.ResearchTask;
import com.hechang.insighthub.model.entity.TaskPlanRevision;
import com.hechang.insighthub.redis.TaskSlotTracker;
import com.hechang.insighthub.redis.WorkspaceConcurrencyService;
import com.hechang.insighthub.service.AuditLogService;
import com.hechang.insighthub.service.WorkspaceAccessService;

@ExtendWith(MockitoExtension.class)
class PlanApplicationServiceImplTest {

    @Mock private ResearchTaskMapper taskMapper;
    @Mock private TaskPlanRevisionMapper revisionMapper;
    @Mock private WorkspaceAccessService accessService;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @Mock private AuditLogService auditLogService;
    @Mock private TaskDispatchOutboxMapper outboxMapper;
    @Mock private WorkspaceConcurrencyService concurrencyService;
    @Mock private TaskSlotTracker slotTracker;
    @Mock private TaskProperties taskProperties;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private TaskEventService taskEventService;

    @InjectMocks private PlanApplicationServiceImpl service;

    @BeforeEach
    void configureBaseMapper() {
        ReflectionTestUtils.setField(service, "mapper", revisionMapper);
    }

    @Test
    void plannerResultAllocatesTheNextDatabaseRevisionInsteadOfTrustingAgentRevisionOne() {
        ResearchTask task = new ResearchTask();
        task.setId("task-1");
        task.setWorkspaceId("workspace-1");
        task.setStatus("RUNNING");
        when(taskMapper.findByIdAndWorkspaceForUpdate("task-1", "workspace-1")).thenReturn(task);

        TaskPlanRevision previous = new TaskPlanRevision();
        previous.setRevisionNo(1);
        when(revisionMapper.findLatestByTask("task-1")).thenReturn(previous);
        when(revisionMapper.insert(any(TaskPlanRevision.class), any(Boolean.class))).thenReturn(1);
        when(taskMapper.updatePlanProjection(
                any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        service.recordPlannerResult(
                "task-1",
                "workspace-1",
                "user-1",
                "run-2",
                Map.of(
                        "planRevision", 1,
                        "planHash", "hash-2",
                        "plan", Map.of("goal", "updated plan", "tasks", java.util.List.of())));

        ArgumentCaptor<TaskPlanRevision> saved = ArgumentCaptor.forClass(TaskPlanRevision.class);
        verify(revisionMapper).insert(saved.capture(), any(Boolean.class));
        assertEquals(2, saved.getValue().getRevisionNo());
        assertEquals("hash-2", saved.getValue().getPlanHash());
        verify(taskMapper).updatePlanProjection(
                "task-1", "workspace-1", saved.getValue().getId(), saved.getValue().getPlanJson(),
                0, "run-2", "WAITING_APPROVAL");
    }
}
