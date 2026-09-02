package com.hechang.insighthub.service.task;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.hechang.insighthub.config.TaskProperties;
import com.hechang.insighthub.exception.BusinessException;
import com.hechang.insighthub.exception.ErrorCode;
import com.hechang.insighthub.integration.AgentControlClient;
import com.hechang.insighthub.mapper.ResearchTaskMapper;
import com.hechang.insighthub.mapper.TaskPlanRevisionMapper;
import com.hechang.insighthub.model.dto.task.CreateTaskAcceptedResponse;
import com.hechang.insighthub.model.dto.task.TaskControlResponse;
import com.hechang.insighthub.model.entity.ResearchTask;
import com.hechang.insighthub.model.enums.TaskStatus;
import com.hechang.insighthub.redis.TaskControlRedis;
import com.hechang.insighthub.redis.TaskCreateRateLimiter;
import com.hechang.insighthub.redis.TaskSlotTracker;
import com.hechang.insighthub.redis.WorkspaceConcurrencyService;
import com.hechang.insighthub.service.CurrentWorkspaceAccess;
import com.hechang.insighthub.service.TaskExecutionService;
import com.hechang.insighthub.service.WorkspaceAccessService;
import com.hechang.insighthub.service.realtime.TaskEventSseHub;

import lombok.RequiredArgsConstructor;

/**
 * 研究任务控制面的单一协调器，负责 pause/resume/cancel/retry 的跨存储一致性。
 *
 * <p>数据库、Java Redis 和 Ubuntu Agent 无法组成一个分布式事务，因此每个命令
 * 都先选择安全的主顺序，再对后续失败执行显式补偿。普通任务 CRUD 与查询不放入
 * 本类，避免 {@code ResearchTaskServiceImpl} 同时承担状态机和 HTTP 查询门面。</p>
 */
@Component
@RequiredArgsConstructor
public class TaskLifecycleCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TaskLifecycleCoordinator.class);

    private final ResearchTaskMapper taskMapper;
    private final TaskPlanRevisionMapper taskPlanRevisionMapper;
    private final WorkspaceAccessService accessService;
    private final TaskStateMachine stateMachine;
    private final TransactionTemplate transactionTemplate;
    private final TaskExecutionService taskExecutionService;
    private final TaskControlRedis taskControlRedis;
    private final AgentControlClient agentControlClient;
    private final WorkspaceConcurrencyService concurrencyService;
    private final TaskSlotTracker slotTracker;
    private final TaskCreateRateLimiter rateLimiter;
    private final TaskEventSseHub sseHub;
    private final TaskStreamLease streamLease;
    private final TaskEventService taskEventService;
    private final TaskProperties taskProperties;

    public TaskControlResponse pause(String workspaceId, String taskId) {
        requireControllableTask(workspaceId, taskId);
        int ttl = controlTtl();
        agentControlClient.setControl(taskId, TaskControlRedis.CONTROL_PAUSED, ttl);
        try {
            transactionTemplate.executeWithoutResult(ignored -> {
                ResearchTask locked = requireTaskForUpdate(workspaceId, taskId);
                stateMachine.transition(TaskStatus.valueOf(locked.getStatus()), TaskStatus.PAUSING);
                moveStatusIfCurrent(
                        taskId, workspaceId, TaskStatus.RUNNING, TaskStatus.PAUSING, null, null, "pausing");
            });
            taskControlRedis.setControl(taskId, TaskControlRedis.CONTROL_PAUSED, ttl);
        } catch (RuntimeException failure) {
            trySetAgentControl(taskId, TaskControlRedis.CONTROL_RUNNING, ttl);
            throw failure;
        }
        return new TaskControlResponse(taskId, TaskStatus.PAUSING.name());
    }

    public TaskControlResponse resume(String workspaceId, String taskId) {
        ResearchTask row = requireControllableTask(workspaceId, taskId);
        stateMachine.transition(TaskStatus.valueOf(row.getStatus()), TaskStatus.RUNNING);
        int ttl = controlTtl();
        String permitId = concurrencyService.tryAcquire(workspaceId, ttl);
        try {
            slotTracker.markHeld(taskId, workspaceId, permitId, ttl);
            transactionTemplate.executeWithoutResult(ignored -> {
                ResearchTask locked = requireTaskForUpdate(workspaceId, taskId);
                TaskStatus from = TaskStatus.valueOf(locked.getStatus());
                if (from != TaskStatus.PAUSED) {
                    throw BusinessException.conflict("TASK_NOT_PAUSED", "only a paused task can be resumed");
                }
                stateMachine.transition(from, TaskStatus.RUNNING);
                moveStatusIfCurrent(taskId, workspaceId, from, TaskStatus.RUNNING, null, null, "resuming");
            });
        } catch (RuntimeException failure) {
            releaseTaskSlot(taskId, workspaceId);
            throw failure;
        }

        streamLease.invalidate(taskId);
        try {
            agentControlClient.setControl(taskId, TaskControlRedis.CONTROL_RUNNING, ttl);
            taskControlRedis.setControl(taskId, TaskControlRedis.CONTROL_RUNNING, ttl);
            taskExecutionService.executeStream(
                    taskId, workspaceId, row.getCreatorId(), row.getQuery(), row.getTraceId(), true,
                    row.getCurrentRunId(), 1);
        } catch (RejectedExecutionException failure) {
            restorePausedAfterResumeFailure(taskId, workspaceId, ttl);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "EXECUTOR_REJECTED: task executor busy");
        } catch (RuntimeException failure) {
            restorePausedAfterResumeFailure(taskId, workspaceId, ttl);
            throw failure;
        }
        return new TaskControlResponse(taskId, TaskStatus.RUNNING.name());
    }

    public TaskControlResponse cancel(String workspaceId, String taskId) {
        requireControllableTask(workspaceId, taskId);
        int ttl = controlTtl();
        agentControlClient.setControl(taskId, TaskControlRedis.CONTROL_CANCELLED, ttl);
        TaskEventService.StoredEvent cancelled;
        try {
            cancelled = transactionTemplate.execute(ignored -> {
                ResearchTask row = requireTaskForUpdate(workspaceId, taskId);
                TaskStatus from = TaskStatus.valueOf(row.getStatus());
                stateMachine.transition(from, TaskStatus.CANCELLED);
                taskMapper.updateTaskFinished(
                        taskId, workspaceId, TaskStatus.CANCELLED.name(), row.getCurrentRunId(),
                        "CANCELLED", "cancelled by user");
                return taskEventService.insertTerminalResult(
                        taskId,
                        row.getCurrentRunId(),
                        TaskStatus.CANCELLED.name(),
                        Map.of("code", "CANCELLED", "message", "cancelled by user"));
            });
        } catch (RuntimeException failure) {
            trySetAgentControl(taskId, TaskControlRedis.CONTROL_RUNNING, ttl);
            throw failure;
        }
        streamLease.invalidate(taskId);
        taskControlRedis.setControl(taskId, TaskControlRedis.CONTROL_CANCELLED, ttl);
        publishStoredEvent(taskId, cancelled);
        releaseTaskSlot(taskId, workspaceId);
        sseHub.completeTask(taskId);
        return new TaskControlResponse(taskId, TaskStatus.CANCELLED.name());
    }

    public CreateTaskAcceptedResponse retry(String workspaceId, String taskId) {
        CurrentWorkspaceAccess actor = accessService.requireCurrentMember(workspaceId);
        ResearchTask row = requireControllableTask(workspaceId, taskId, actor);
        rateLimiter.acquire(actor.userId());
        int ttl = controlTtl();
        String permitId = concurrencyService.tryAcquire(workspaceId, ttl);
        String runId = "run-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Integer nextRevision;
        try {
            slotTracker.markHeld(taskId, workspaceId, permitId, ttl);
            nextRevision = transactionTemplate.execute(ignored -> {
                ResearchTask locked = requireTaskForUpdate(workspaceId, taskId);
                TaskStatus from = TaskStatus.valueOf(locked.getStatus());
                boolean qualityRetry = from == TaskStatus.COMPLETED
                        && ("FAIL".equals(locked.getQualityStatus())
                        || "LEGACY_SYNTHETIC".equals(locked.getQualityStatus()));
                if (from != TaskStatus.FAILED && !qualityRetry) {
                    throw BusinessException.conflict(
                            "TASK_NOT_RETRYABLE", "task has no failed quality result to retry");
                }
                int revision = nextPlanRevisionNo(taskId);
                stateMachine.transition(from, TaskStatus.RUNNING);
                taskMapper.prepareRetry(taskId, workspaceId, runId);
                moveStatusIfCurrent(taskId, workspaceId, from, TaskStatus.RUNNING, 30, "retry", "retrying");
                return revision;
            });
        } catch (RuntimeException failure) {
            releaseTaskSlot(taskId, workspaceId);
            throw failure;
        }

        try {
            agentControlClient.setControl(taskId, TaskControlRedis.CONTROL_RUNNING, ttl);
            taskControlRedis.setControl(taskId, TaskControlRedis.CONTROL_RUNNING, ttl);
            taskExecutionService.executeStream(
                    taskId, workspaceId, row.getCreatorId(), row.getQuery(), row.getTraceId(), false,
                    runId, nextRevision == null ? 1 : nextRevision);
        } catch (RejectedExecutionException failure) {
            markFailedSync(taskId, workspaceId, runId, "EXECUTOR_REJECTED", "task executor queue full");
            trySetAgentControl(taskId, TaskControlRedis.CONTROL_CANCELLED, ttl);
            releaseTaskSlot(taskId, workspaceId);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "EXECUTOR_REJECTED: task executor busy");
        } catch (RuntimeException failure) {
            markFailedSync(
                    taskId, workspaceId, runId,
                    "RETRY_SUBMIT_FAILED", "retry execution could not be submitted");
            trySetAgentControl(taskId, TaskControlRedis.CONTROL_CANCELLED, ttl);
            releaseTaskSlot(taskId, workspaceId);
            throw failure;
        }
        return new CreateTaskAcceptedResponse(taskId, TaskStatus.RUNNING.name(), row.getTraceId());
    }

    private ResearchTask requireControllableTask(String workspaceId, String taskId) {
        return requireControllableTask(workspaceId, taskId, accessService.requireCurrentMember(workspaceId));
    }

    private ResearchTask requireControllableTask(
            String workspaceId, String taskId, CurrentWorkspaceAccess actor) {
        ResearchTask task = taskMapper.findByIdAndWorkspace(taskId, workspaceId);
        if (task == null) {
            throw BusinessException.notFound("task not found");
        }
        if (!actor.userId().equals(task.getCreatorId()) && !actor.role().isAdminOrAbove()) {
            throw BusinessException.forbidden("only task creator or workspace admin may control task");
        }
        return task;
    }

    private ResearchTask requireTaskForUpdate(String workspaceId, String taskId) {
        ResearchTask task = taskMapper.findByIdAndWorkspaceForUpdate(taskId, workspaceId);
        if (task == null) {
            throw BusinessException.notFound("task not found");
        }
        return task;
    }

    private int nextPlanRevisionNo(String taskId) {
        var latest = taskPlanRevisionMapper.findLatestByTask(taskId);
        return latest == null ? 1 : latest.getRevisionNo() + 1;
    }

    private void moveStatusIfCurrent(
            String taskId, String workspaceId, TaskStatus from, TaskStatus to,
            Integer progress, String node, String action) {
        if (taskMapper.updateStatusIfCurrent(
                taskId, workspaceId, from.name(), to.name(), progress, node) != 1) {
            throw BusinessException.conflict("TASK_STATE_CHANGED", "task status changed while " + action);
        }
    }

    private void restorePausedAfterResumeFailure(String taskId, String workspaceId, int ttl) {
        taskControlRedis.setControl(taskId, TaskControlRedis.CONTROL_PAUSED, ttl);
        trySetAgentControl(taskId, TaskControlRedis.CONTROL_PAUSED, ttl);
        taskMapper.updateStatusIfCurrent(
                taskId, workspaceId, TaskStatus.RUNNING.name(), TaskStatus.PAUSED.name(), null, null);
        releaseTaskSlot(taskId, workspaceId);
    }

    private void markFailedSync(
            String taskId, String workspaceId, String runId, String code, String message) {
        transactionTemplate.executeWithoutResult(ignored -> {
            ResearchTask row = taskMapper.findByIdAndWorkspaceForUpdate(taskId, workspaceId);
            if (row == null || TaskStatus.isTerminal(row.getStatus())) {
                return;
            }
            taskMapper.updateTaskFinished(
                    taskId, workspaceId, TaskStatus.FAILED.name(),
                    runId == null ? row.getCurrentRunId() : runId, code, message);
        });
    }

    private void publishStoredEvent(String taskId, TaskEventService.StoredEvent event) {
        if (event == null) return;
        taskControlRedis.publishEvent(taskId, event.json());
        sseHub.broadcastLocal(taskId, event.eventNo(), "TASK_RESULT", event.json());
    }

    private void releaseTaskSlot(String taskId, String workspaceId) {
        slotTracker.releaseOnce(
                taskId, workspaceId,
                permit -> concurrencyService.release(workspaceId, permit));
    }

    private int controlTtl() {
        return taskProperties.getDefaultTimeoutSeconds() + 600;
    }

    private void trySetAgentControl(String taskId, String value, int ttl) {
        try {
            agentControlClient.setControl(taskId, value, ttl);
        } catch (RuntimeException compensationFailure) {
            log.warn("Agent control compensation failed taskId={} value={} type={}",
                    taskId, value, compensationFailure.getClass().getSimpleName());
        }
    }
}
