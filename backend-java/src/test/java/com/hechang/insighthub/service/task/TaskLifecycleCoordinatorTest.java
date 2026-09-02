package com.hechang.insighthub.service.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.hechang.insighthub.config.TaskProperties;
import com.hechang.insighthub.integration.AgentControlClient;
import com.hechang.insighthub.mapper.ResearchTaskMapper;
import com.hechang.insighthub.mapper.TaskPlanRevisionMapper;
import com.hechang.insighthub.model.entity.ResearchTask;
import com.hechang.insighthub.model.enums.WorkspaceRole;
import com.hechang.insighthub.redis.TaskControlRedis;
import com.hechang.insighthub.redis.TaskCreateRateLimiter;
import com.hechang.insighthub.redis.TaskSlotTracker;
import com.hechang.insighthub.redis.WorkspaceConcurrencyService;
import com.hechang.insighthub.service.CurrentWorkspaceAccess;
import com.hechang.insighthub.service.TaskExecutionService;
import com.hechang.insighthub.service.WorkspaceAccessService;
import com.hechang.insighthub.service.realtime.TaskEventSseHub;

@ExtendWith(MockitoExtension.class)
class TaskLifecycleCoordinatorTest {

    @Mock private ResearchTaskMapper taskMapper;
    @Mock private TaskPlanRevisionMapper planRevisionMapper;
    @Mock private WorkspaceAccessService accessService;
    @Mock private TaskStateMachine stateMachine;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private TaskExecutionService executionService;
    @Mock private TaskControlRedis taskControlRedis;
    @Mock private AgentControlClient agentControlClient;
    @Mock private WorkspaceConcurrencyService concurrencyService;
    @Mock private TaskSlotTracker slotTracker;
    @Mock private TaskCreateRateLimiter rateLimiter;
    @Mock private TaskEventSseHub sseHub;
    @Mock private TaskStreamLease streamLease;
    @Mock private TaskEventService taskEventService;
    @Mock private TaskProperties taskProperties;

    @InjectMocks private TaskLifecycleCoordinator coordinator;

    @Test
    void pauseSignalsAgentBeforePublishingJavaControl() {
        ResearchTask task = task("RUNNING");
        arrangeTask(task);
        when(taskMapper.updateStatusIfCurrent(
                "task-1", "workspace-1", "RUNNING", "PAUSING", null, null)).thenReturn(1);
        runTransactionImmediately();

        var response = coordinator.pause("workspace-1", "task-1");

        assertEquals("PAUSING", response.getStatus());
        var order = inOrder(agentControlClient, taskControlRedis);
        order.verify(agentControlClient).setControl("task-1", TaskControlRedis.CONTROL_PAUSED, 1500);
        order.verify(taskControlRedis).setControl("task-1", TaskControlRedis.CONTROL_PAUSED, 1500);
    }

    @Test
    void resumeTracksNewPermitAndSubmitsExecution() {
        ResearchTask task = task("PAUSED");
        arrangeTask(task);
        when(taskMapper.updateStatusIfCurrent(
                "task-1", "workspace-1", "PAUSED", "RUNNING", null, null)).thenReturn(1);
        when(concurrencyService.tryAcquire("workspace-1", 1500)).thenReturn("permit-1");
        runTransactionImmediately();

        var response = coordinator.resume("workspace-1", "task-1");

        assertEquals("RUNNING", response.getStatus());
        verify(slotTracker).markHeld("task-1", "workspace-1", "permit-1", 1500);
        verify(executionService).executeStream(
                "task-1", "workspace-1", "user-1", "query", "trace-1", true, "run-1", 1);
    }

    @Test
    void resumeRestoresPausedStateWhenAgentControlFails() {
        ResearchTask task = task("PAUSED");
        arrangeTask(task);
        when(taskMapper.updateStatusIfCurrent(
                "task-1", "workspace-1", "PAUSED", "RUNNING", null, null)).thenReturn(1);
        when(concurrencyService.tryAcquire("workspace-1", 1500)).thenReturn("permit-1");
        doThrow(new IllegalStateException("agent unavailable"))
                .when(agentControlClient)
                .setControl("task-1", TaskControlRedis.CONTROL_RUNNING, 1500);
        runTransactionImmediately();

        assertThrows(IllegalStateException.class, () -> coordinator.resume("workspace-1", "task-1"));

        verify(taskControlRedis).setControl("task-1", TaskControlRedis.CONTROL_PAUSED, 1500);
        verify(agentControlClient).setControl("task-1", TaskControlRedis.CONTROL_PAUSED, 1500);
        verify(taskMapper).updateStatusIfCurrent(
                "task-1", "workspace-1", "RUNNING", "PAUSED", null, null);
        verify(slotTracker).releaseOnce(any(), any(), any());
    }

    private void arrangeTask(ResearchTask task) {
        when(accessService.requireCurrentMember("workspace-1"))
                .thenReturn(new CurrentWorkspaceAccess("user-1", WorkspaceRole.MEMBER));
        when(taskMapper.findByIdAndWorkspace("task-1", "workspace-1")).thenReturn(task);
        when(taskMapper.findByIdAndWorkspaceForUpdate("task-1", "workspace-1")).thenReturn(task);
        when(taskProperties.getDefaultTimeoutSeconds()).thenReturn(900);
    }

    private void runTransactionImmediately() {
        doAnswer(invocation -> {
            invocation.<java.util.function.Consumer<org.springframework.transaction.TransactionStatus>>getArgument(0)
                    .accept(new SimpleTransactionStatus());
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private static ResearchTask task(String status) {
        ResearchTask task = new ResearchTask();
        task.setId("task-1");
        task.setWorkspaceId("workspace-1");
        task.setCreatorId("user-1");
        task.setQuery("query");
        task.setTraceId("trace-1");
        task.setCurrentRunId("run-1");
        task.setStatus(status);
        return task;
    }
}
