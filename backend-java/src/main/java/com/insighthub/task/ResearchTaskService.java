package com.insighthub.task;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.insighthub.common.BusinessException;
import com.insighthub.config.TaskProperties;
import com.insighthub.integration.AgentServiceClient;
import com.insighthub.redis.TaskControlRedis;
import com.insighthub.redis.TaskCreateRateLimiter;
import com.insighthub.redis.TaskSlotTracker;
import com.insighthub.redis.WorkspaceConcurrencyService;
import com.insighthub.security.SecurityUtils;
import com.insighthub.task.dto.CreateTaskAcceptedResponse;
import com.insighthub.task.dto.TaskControlResponse;
import com.insighthub.task.dto.TaskSummaryResponse;
import com.insighthub.web.dto.AgentTaskResponseDto;
import com.insighthub.workspace.WorkspaceAccessService;

/**
 * 研究任务：异步流式 + 同步兼容 + 控制面。
 */
@Service
public class ResearchTaskService {

    private static final Logger log = LoggerFactory.getLogger(ResearchTaskService.class);

    private final AgentServiceClient agentServiceClient;
    private final TaskRepository taskRepository;
    private final WorkspaceAccessService accessService;
    private final TaskStateMachine stateMachine;
    private final TransactionTemplate transactionTemplate;
    private final TaskExecutionService taskExecutionService;
    private final TaskControlRedis taskControlRedis;
    private final WorkspaceConcurrencyService concurrencyService;
    private final TaskSlotTracker slotTracker;
    private final TaskCreateRateLimiter rateLimiter;
    private final TaskEventSseHub sseHub;
    private final TaskStreamLease streamLease;
    private final TaskProperties taskProperties;

    public ResearchTaskService(
            AgentServiceClient agentServiceClient,
            TaskRepository taskRepository,
            WorkspaceAccessService accessService,
            TaskStateMachine stateMachine,
            TransactionTemplate transactionTemplate,
            TaskExecutionService taskExecutionService,
            TaskControlRedis taskControlRedis,
            WorkspaceConcurrencyService concurrencyService,
            TaskSlotTracker slotTracker,
            TaskCreateRateLimiter rateLimiter,
            TaskEventSseHub sseHub,
            TaskStreamLease streamLease,
            TaskProperties taskProperties) {
        this.agentServiceClient = agentServiceClient;
        this.taskRepository = taskRepository;
        this.accessService = accessService;
        this.stateMachine = stateMachine;
        this.transactionTemplate = transactionTemplate;
        this.taskExecutionService = taskExecutionService;
        this.taskControlRedis = taskControlRedis;
        this.concurrencyService = concurrencyService;
        this.slotTracker = slotTracker;
        this.rateLimiter = rateLimiter;
        this.sseHub = sseHub;
        this.streamLease = streamLease;
        this.taskProperties = taskProperties;
    }

    /**
     * 异步创建任务：202 + 后台拉 Python 流。
     */
    public CreateTaskAcceptedResponse createAsync(String workspaceId, String query) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);
        rateLimiter.acquire(userId);
        // true=占用了 Redis 许可；false=Redis 降级放行（勿 markHeld，避免恢复后抬高 permits）
        boolean slotAcquired = concurrencyService.tryAcquire(workspaceId);

        String taskId = "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String traceId = "trace-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        int ttl = taskProperties.getDefaultTimeoutSeconds() + 600;
        if (slotAcquired) {
            slotTracker.markHeld(taskId, workspaceId, ttl);
        }

        try {
            taskRepository.insertCreatedTask(taskId, workspaceId, userId, query, traceId);
            advance(taskId, workspaceId, TaskStatus.CREATED, TaskStatus.PLANNING, 10, "create_plan");
            advance(taskId, workspaceId, TaskStatus.PLANNING, TaskStatus.RUNNING, 30, "dispatch_tasks");
            taskControlRedis.setControl(taskId, TaskControlRedis.CONTROL_RUNNING, ttl);
            taskExecutionService.executeStream(taskId, workspaceId, userId, query, traceId, false);
        } catch (RejectedExecutionException ex) {
            taskRepository.updateTaskFinished(
                    taskId, workspaceId, TaskStatus.FAILED.name(), null,
                    "EXECUTOR_REJECTED", "task executor queue full");
            slotTracker.releaseOnce(taskId, workspaceId, () -> concurrencyService.release(workspaceId));
            throw new BusinessException("EXECUTOR_REJECTED", "task executor busy", HttpStatus.SERVICE_UNAVAILABLE);
        } catch (RuntimeException ex) {
            slotTracker.releaseOnce(taskId, workspaceId, () -> concurrencyService.release(workspaceId));
            throw ex;
        }

        log.info("Async research task {} workspace={} traceId={}", taskId, workspaceId, traceId);
        return new CreateTaskAcceptedResponse(taskId, TaskStatus.RUNNING.name(), traceId);
    }

    /**
     * 同步执行（week1/2 兼容）。
     */
    public AgentTaskResponseDto createAndRun(String workspaceId, String query) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);

        String taskId = "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String traceId = "trace-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        taskRepository.insertCreatedTask(taskId, workspaceId, userId, query, traceId);
        advance(taskId, workspaceId, TaskStatus.CREATED, TaskStatus.PLANNING, 10, "create_plan");
        advance(taskId, workspaceId, TaskStatus.PLANNING, TaskStatus.RUNNING, 30, "dispatch_tasks");

        AgentTaskResponseDto response;
        try {
            response = agentServiceClient.createTask(taskId, workspaceId, userId, query, traceId);
        } catch (Exception ex) {
            log.error("Agent call failed taskId={} workspace={}", taskId, workspaceId, ex);
            markFailedSync(taskId, workspaceId, "AGENT_CALL_FAILED", "agent service call failed");
            throw new BusinessException("AGENT_CALL_FAILED", "agent service call failed", HttpStatus.BAD_GATEWAY);
        }
        if (response == null) {
            markFailedSync(taskId, workspaceId, "AGENT_EMPTY_RESPONSE", "agent service returned empty body");
            throw new BusinessException("AGENT_EMPTY_RESPONSE", "agent service returned empty body", HttpStatus.BAD_GATEWAY);
        }
        response.setTraceId(traceId);

        String status = response.getStatus() == null ? "FAILED" : response.getStatus();
        String errorCode = null;
        String errorMessage = null;
        if (response.getError() != null) {
            errorCode = String.valueOf(response.getError().getOrDefault("code", "AGENT_ERROR"));
            errorMessage = String.valueOf(response.getError().getOrDefault("message", ""));
        }

        final String finalStatus = status;
        final String finalErrorCode = errorCode;
        final String finalErrorMessage = errorMessage;
        transactionTemplate.executeWithoutResult(tx -> {
            if ("COMPLETED".equalsIgnoreCase(finalStatus)) {
                advance(taskId, workspaceId, TaskStatus.RUNNING, TaskStatus.GENERATING, 80, "write_report");
                advance(taskId, workspaceId, TaskStatus.GENERATING, TaskStatus.COMPLETED, 100, "finalize");
                taskRepository.updateTaskFinished(
                        taskId, workspaceId, TaskStatus.COMPLETED.name(), response.getRunId(), null, null);
                if (response.getReportMarkdown() != null) {
                    String reportId = "report-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                    taskRepository.insertReport(
                            reportId, taskId, workspaceId, response.getReportMarkdown(),
                            extractTitle(response.getReportMarkdown()));
                }
            } else {
                advance(taskId, workspaceId, TaskStatus.RUNNING, TaskStatus.FAILED, 30, null);
                taskRepository.updateTaskFinished(
                        taskId, workspaceId, TaskStatus.FAILED.name(), response.getRunId(),
                        finalErrorCode, finalErrorMessage);
            }
            taskRepository.insertEvents(taskId, response.getEvents());
        });

        response.setTaskId(taskId);
        response.setStatus(status.toUpperCase());
        return response;
    }

    public List<TaskSummaryResponse> list(String workspaceId) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);
        return taskRepository.listByWorkspace(workspaceId).stream().map(this::toSummary).toList();
    }

    public TaskSummaryResponse get(String workspaceId, String taskId) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);
        return taskRepository.findByIdAndWorkspace(taskId, workspaceId)
                .map(this::toSummary)
                .orElseThrow(() -> BusinessException.notFound("task not found"));
    }

    public SseEmitter streamEvents(String workspaceId, String taskId, long fromEventNo) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);
        return sseHub.subscribe(taskId, workspaceId, fromEventNo);
    }

    public TaskControlResponse pause(String workspaceId, String taskId) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);
        TaskRepository.TaskRow row = requireTask(workspaceId, taskId);
        stateMachine.transition(TaskStatus.valueOf(row.status()), TaskStatus.PAUSED);
        // 不在此处 invalidate：需让当前 consumer 收完 TASK_PAUSED/TASK_RESULT；
        // 侧效已对 PAUSED 忽略 NODE_*；resume/cancel 再抢占世代
        taskControlRedis.setControl(
                taskId, TaskControlRedis.CONTROL_PAUSED, taskProperties.getDefaultTimeoutSeconds() + 600);
        taskRepository.updateStatus(taskId, workspaceId, TaskStatus.PAUSED.name(), null, null);
        return new TaskControlResponse(taskId, TaskStatus.PAUSED.name());
    }

    public TaskControlResponse resume(String workspaceId, String taskId) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);
        TaskRepository.TaskRow row = requireTask(workspaceId, taskId);
        stateMachine.transition(TaskStatus.valueOf(row.status()), TaskStatus.RUNNING);
        taskControlRedis.setControl(
                taskId, TaskControlRedis.CONTROL_RUNNING, taskProperties.getDefaultTimeoutSeconds() + 600);
        taskRepository.updateStatus(taskId, workspaceId, TaskStatus.RUNNING.name(), null, null);
        try {
            taskExecutionService.executeStream(
                    taskId, workspaceId, row.creatorId(), row.query(), row.traceId(), true);
        } catch (RejectedExecutionException ex) {
            // 回滚为 PAUSED，避免无 worker 的 RUNNING 脏状态
            taskControlRedis.setControl(
                    taskId, TaskControlRedis.CONTROL_PAUSED, taskProperties.getDefaultTimeoutSeconds() + 600);
            taskRepository.updateStatus(taskId, workspaceId, TaskStatus.PAUSED.name(), null, null);
            throw new BusinessException("EXECUTOR_REJECTED", "task executor busy", HttpStatus.SERVICE_UNAVAILABLE);
        }
        return new TaskControlResponse(taskId, TaskStatus.RUNNING.name());
    }

    public TaskControlResponse cancel(String workspaceId, String taskId) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);
        TaskRepository.TaskRow row = requireTask(workspaceId, taskId);
        TaskStatus from = TaskStatus.valueOf(row.status());
        if (from == TaskStatus.COMPLETED || from == TaskStatus.CANCELLED) {
            throw BusinessException.conflict("INVALID_STATUS_TRANSITION", "cannot cancel from " + from);
        }
        try {
            stateMachine.transition(from, TaskStatus.CANCELLED);
        } catch (BusinessException ex) {
            // 允许 CREATED/PLANNING/RUNNING/PAUSED/GENERATING
            if (from != TaskStatus.CREATED && from != TaskStatus.PLANNING
                    && from != TaskStatus.RUNNING && from != TaskStatus.PAUSED
                    && from != TaskStatus.GENERATING) {
                throw ex;
            }
        }
        streamLease.invalidate(taskId);
        taskControlRedis.setControl(
                taskId, TaskControlRedis.CONTROL_CANCELLED, taskProperties.getDefaultTimeoutSeconds() + 600);
        taskRepository.updateTaskFinished(
                taskId, workspaceId, TaskStatus.CANCELLED.name(), row.currentRunId(), "CANCELLED", "cancelled by user");
        slotTracker.releaseOnce(taskId, workspaceId, () -> concurrencyService.release(workspaceId));
        sseHub.completeTask(taskId);
        return new TaskControlResponse(taskId, TaskStatus.CANCELLED.name());
    }

    public CreateTaskAcceptedResponse retry(String workspaceId, String taskId) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);
        TaskRepository.TaskRow row = requireTask(workspaceId, taskId);
        stateMachine.transition(TaskStatus.valueOf(row.status()), TaskStatus.RUNNING);
        rateLimiter.acquire(userId);
        boolean slotAcquired = concurrencyService.tryAcquire(workspaceId);
        int ttl = taskProperties.getDefaultTimeoutSeconds() + 600;
        if (slotAcquired) {
            slotTracker.markHeld(taskId, workspaceId, ttl);
        }

        String runId = "run-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        taskRepository.prepareRetry(taskId, workspaceId, runId);
        taskRepository.updateStatus(taskId, workspaceId, TaskStatus.RUNNING.name(), 30, "retry");
        taskControlRedis.setControl(taskId, TaskControlRedis.CONTROL_RUNNING, ttl);
        try {
            // 全量 stream 重跑同 taskId（event_no 继续递增）
            taskExecutionService.executeStream(
                    taskId, workspaceId, row.creatorId(), row.query(), row.traceId(), false);
        } catch (RejectedExecutionException ex) {
            slotTracker.releaseOnce(taskId, workspaceId, () -> concurrencyService.release(workspaceId));
            throw new BusinessException("EXECUTOR_REJECTED", "task executor busy", HttpStatus.SERVICE_UNAVAILABLE);
        } catch (RuntimeException ex) {
            slotTracker.releaseOnce(taskId, workspaceId, () -> concurrencyService.release(workspaceId));
            throw ex;
        }
        return new CreateTaskAcceptedResponse(taskId, TaskStatus.RUNNING.name(), row.traceId());
    }

    private TaskRepository.TaskRow requireTask(String workspaceId, String taskId) {
        return taskRepository.findByIdAndWorkspace(taskId, workspaceId)
                .orElseThrow(() -> BusinessException.notFound("task not found"));
    }

    private void markFailedSync(String taskId, String workspaceId, String code, String message) {
        transactionTemplate.executeWithoutResult(tx -> {
            advance(taskId, workspaceId, TaskStatus.RUNNING, TaskStatus.FAILED, 30, null);
            taskRepository.updateTaskFinished(
                    taskId, workspaceId, TaskStatus.FAILED.name(), null, code, message);
        });
    }

    private void advance(
            String taskId,
            String workspaceId,
            TaskStatus from,
            TaskStatus to,
            int progress,
            String node) {
        stateMachine.transition(from, to);
        taskRepository.updateStatus(taskId, workspaceId, to.name(), progress, node);
    }

    private TaskSummaryResponse toSummary(TaskRepository.TaskRow row) {
        TaskSummaryResponse r = new TaskSummaryResponse();
        r.setTaskId(row.id());
        r.setWorkspaceId(row.workspaceId());
        r.setCreatorId(row.creatorId());
        r.setQuery(row.query());
        r.setStatus(row.status());
        r.setProgress(row.progress());
        r.setTraceId(row.traceId());
        r.setRunId(row.currentRunId());
        r.setErrorCode(row.errorCode());
        r.setErrorMessage(row.errorMessage());
        r.setCreatedAt(row.createdAt());
        return r;
    }

    private static String extractTitle(String markdown) {
        for (String line : markdown.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                return trimmed.replaceFirst("^#+\\s*", "");
            }
        }
        return "InsightHub Report";
    }
}
