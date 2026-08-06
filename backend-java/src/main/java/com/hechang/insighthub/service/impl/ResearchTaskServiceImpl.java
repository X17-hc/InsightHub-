package com.hechang.insighthub.service.impl;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hechang.insighthub.config.TaskProperties;
import com.hechang.insighthub.exception.BusinessException;
import com.hechang.insighthub.exception.ErrorCode;
import com.hechang.insighthub.integration.AgentServiceClient;
import com.hechang.insighthub.mapper.ReportMapper;
import com.hechang.insighthub.mapper.ResearchTaskMapper;
import com.hechang.insighthub.mapper.TaskEventMapper;
import com.hechang.insighthub.model.dto.task.AgentEventDto;
import com.hechang.insighthub.model.dto.task.AgentTaskResponseDto;
import com.hechang.insighthub.model.dto.task.CreateTaskAcceptedResponse;
import com.hechang.insighthub.model.dto.task.TaskControlResponse;
import com.hechang.insighthub.model.dto.task.TaskSummaryResponse;
import com.hechang.insighthub.model.entity.Report;
import com.hechang.insighthub.model.entity.ResearchTask;
import com.hechang.insighthub.model.enums.TaskStatus;
import com.hechang.insighthub.redis.TaskControlRedis;
import com.hechang.insighthub.redis.TaskCreateRateLimiter;
import com.hechang.insighthub.redis.TaskSlotTracker;
import com.hechang.insighthub.redis.WorkspaceConcurrencyService;
import com.hechang.insighthub.security.SecurityUtils;
import com.hechang.insighthub.service.ResearchTaskService;
import com.hechang.insighthub.service.TaskExecutionService;
import com.hechang.insighthub.service.WorkspaceAccessService;
import com.mybatisflex.spring.service.impl.ServiceImpl;

/**
 * 研究任务：异步流式 + 同步兼容 + 控制面实现。
 */
@Service
public class ResearchTaskServiceImpl extends ServiceImpl<ResearchTaskMapper, ResearchTask>
        implements ResearchTaskService {

    private static final Logger log = LoggerFactory.getLogger(ResearchTaskServiceImpl.class);

    private final AgentServiceClient agentServiceClient;
    private final TaskEventMapper taskEventMapper;
    private final ReportMapper reportMapper;
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
    private final ObjectMapper objectMapper;

    public ResearchTaskServiceImpl(
            AgentServiceClient agentServiceClient,
            TaskEventMapper taskEventMapper,
            ReportMapper reportMapper,
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
            TaskProperties taskProperties,
            ObjectMapper objectMapper) {
        this.agentServiceClient = agentServiceClient;
        this.taskEventMapper = taskEventMapper;
        this.reportMapper = reportMapper;
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
        this.objectMapper = objectMapper;
    }

    @Override
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
            insertCreatedTask(taskId, workspaceId, userId, query, traceId);
            advance(taskId, workspaceId, TaskStatus.CREATED, TaskStatus.PLANNING, 10, "create_plan");
            advance(taskId, workspaceId, TaskStatus.PLANNING, TaskStatus.RUNNING, 30, "dispatch_tasks");
            taskControlRedis.setControl(taskId, TaskControlRedis.CONTROL_RUNNING, ttl);
            taskExecutionService.executeStream(taskId, workspaceId, userId, query, traceId, false);
        } catch (RejectedExecutionException ex) {
            mapper.updateTaskFinished(
                    taskId, workspaceId, TaskStatus.FAILED.name(), null,
                    "EXECUTOR_REJECTED", truncate("task executor queue full", 1024));
            slotTracker.releaseOnce(taskId, workspaceId, () -> concurrencyService.release(workspaceId));
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "EXECUTOR_REJECTED: task executor busy");
        } catch (RuntimeException ex) {
            slotTracker.releaseOnce(taskId, workspaceId, () -> concurrencyService.release(workspaceId));
            throw ex;
        }

        log.info("Async research task {} workspace={} traceId={}", taskId, workspaceId, traceId);
        return new CreateTaskAcceptedResponse(taskId, TaskStatus.RUNNING.name(), traceId);
    }

    @Override
    public AgentTaskResponseDto createAndRun(String workspaceId, String query) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);

        String taskId = "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String traceId = "trace-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        insertCreatedTask(taskId, workspaceId, userId, query, traceId);
        advance(taskId, workspaceId, TaskStatus.CREATED, TaskStatus.PLANNING, 10, "create_plan");
        advance(taskId, workspaceId, TaskStatus.PLANNING, TaskStatus.RUNNING, 30, "dispatch_tasks");

        AgentTaskResponseDto response;
        try {
            response = agentServiceClient.createTask(taskId, workspaceId, userId, query, traceId);
        } catch (Exception ex) {
            log.error("Agent call failed taskId={} workspace={}", taskId, workspaceId, ex);
            markFailedSync(taskId, workspaceId, "AGENT_CALL_FAILED", "agent service call failed");
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AGENT_CALL_FAILED: agent service call failed");
        }
        if (response == null) {
            markFailedSync(taskId, workspaceId, "AGENT_EMPTY_RESPONSE", "agent service returned empty body");
            throw new BusinessException(
                    ErrorCode.SYSTEM_ERROR, "AGENT_EMPTY_RESPONSE: agent service returned empty body");
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
                mapper.updateTaskFinished(
                        taskId, workspaceId, TaskStatus.COMPLETED.name(), response.getRunId(), null, null);
                if (response.getReportMarkdown() != null) {
                    String reportId = "report-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                    insertReport(reportId, taskId, workspaceId, response.getReportMarkdown(),
                            extractTitle(response.getReportMarkdown()));
                }
            } else {
                advance(taskId, workspaceId, TaskStatus.RUNNING, TaskStatus.FAILED, 30, null);
                mapper.updateTaskFinished(
                        taskId, workspaceId, TaskStatus.FAILED.name(), response.getRunId(),
                        finalErrorCode, truncate(finalErrorMessage, 1024));
            }
            insertEvents(taskId, response.getEvents());
        });

        response.setTaskId(taskId);
        response.setStatus(status.toUpperCase());
        return response;
    }

    @Override
    public List<TaskSummaryResponse> list(String workspaceId) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);
        return mapper.listByWorkspace(workspaceId).stream().map(this::toSummary).toList();
    }

    @Override
    public TaskSummaryResponse get(String workspaceId, String taskId) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);
        ResearchTask task = mapper.findByIdAndWorkspace(taskId, workspaceId);
        if (task == null) {
            throw BusinessException.notFound("task not found");
        }
        return toSummary(task);
    }

    @Override
    public SseEmitter streamEvents(String workspaceId, String taskId, long fromEventNo) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);
        return sseHub.subscribe(taskId, workspaceId, fromEventNo);
    }

    @Override
    public TaskControlResponse pause(String workspaceId, String taskId) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);
        ResearchTask row = requireTask(workspaceId, taskId);
        stateMachine.transition(TaskStatus.valueOf(row.getStatus()), TaskStatus.PAUSED);
        // 不在此处 invalidate：需让当前 consumer 收完 TASK_PAUSED/TASK_RESULT；
        // 侧效已对 PAUSED 忽略 NODE_*；resume/cancel 再抢占世代
        taskControlRedis.setControl(
                taskId, TaskControlRedis.CONTROL_PAUSED, taskProperties.getDefaultTimeoutSeconds() + 600);
        mapper.updateStatus(taskId, workspaceId, TaskStatus.PAUSED.name(), null, null);
        return new TaskControlResponse(taskId, TaskStatus.PAUSED.name());
    }

    @Override
    public TaskControlResponse resume(String workspaceId, String taskId) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);
        ResearchTask row = requireTask(workspaceId, taskId);
        stateMachine.transition(TaskStatus.valueOf(row.getStatus()), TaskStatus.RUNNING);
        taskControlRedis.setControl(
                taskId, TaskControlRedis.CONTROL_RUNNING, taskProperties.getDefaultTimeoutSeconds() + 600);
        mapper.updateStatus(taskId, workspaceId, TaskStatus.RUNNING.name(), null, null);
        try {
            taskExecutionService.executeStream(
                    taskId, workspaceId, row.getCreatorId(), row.getQuery(), row.getTraceId(), true);
        } catch (RejectedExecutionException ex) {
            // 回滚为 PAUSED，避免无 worker 的 RUNNING 脏状态
            taskControlRedis.setControl(
                    taskId, TaskControlRedis.CONTROL_PAUSED, taskProperties.getDefaultTimeoutSeconds() + 600);
            mapper.updateStatus(taskId, workspaceId, TaskStatus.PAUSED.name(), null, null);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "EXECUTOR_REJECTED: task executor busy");
        }
        return new TaskControlResponse(taskId, TaskStatus.RUNNING.name());
    }

    @Override
    public TaskControlResponse cancel(String workspaceId, String taskId) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);
        ResearchTask row = requireTask(workspaceId, taskId);
        TaskStatus from = TaskStatus.valueOf(row.getStatus());
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
        mapper.updateTaskFinished(
                taskId, workspaceId, TaskStatus.CANCELLED.name(), row.getCurrentRunId(),
                "CANCELLED", truncate("cancelled by user", 1024));
        slotTracker.releaseOnce(taskId, workspaceId, () -> concurrencyService.release(workspaceId));
        sseHub.completeTask(taskId);
        return new TaskControlResponse(taskId, TaskStatus.CANCELLED.name());
    }

    @Override
    public CreateTaskAcceptedResponse retry(String workspaceId, String taskId) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);
        ResearchTask row = requireTask(workspaceId, taskId);
        stateMachine.transition(TaskStatus.valueOf(row.getStatus()), TaskStatus.RUNNING);
        rateLimiter.acquire(userId);
        boolean slotAcquired = concurrencyService.tryAcquire(workspaceId);
        int ttl = taskProperties.getDefaultTimeoutSeconds() + 600;
        if (slotAcquired) {
            slotTracker.markHeld(taskId, workspaceId, ttl);
        }

        String runId = "run-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        mapper.prepareRetry(taskId, workspaceId, runId);
        mapper.updateStatus(taskId, workspaceId, TaskStatus.RUNNING.name(), 30, "retry");
        taskControlRedis.setControl(taskId, TaskControlRedis.CONTROL_RUNNING, ttl);
        try {
            // 全量 stream 重跑同 taskId（event_no 继续递增）
            taskExecutionService.executeStream(
                    taskId, workspaceId, row.getCreatorId(), row.getQuery(), row.getTraceId(), false);
        } catch (RejectedExecutionException ex) {
            slotTracker.releaseOnce(taskId, workspaceId, () -> concurrencyService.release(workspaceId));
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "EXECUTOR_REJECTED: task executor busy");
        } catch (RuntimeException ex) {
            slotTracker.releaseOnce(taskId, workspaceId, () -> concurrencyService.release(workspaceId));
            throw ex;
        }
        return new CreateTaskAcceptedResponse(taskId, TaskStatus.RUNNING.name(), row.getTraceId());
    }

    /** 插入 CREATED 状态任务 */
    private void insertCreatedTask(
            String taskId, String workspaceId, String creatorId, String query, String traceId) {
        ResearchTask task = new ResearchTask();
        task.setId(taskId);
        task.setWorkspaceId(workspaceId);
        task.setCreatorId(creatorId);
        task.setQuery(query);
        task.setStatus(TaskStatus.CREATED.name());
        task.setProgress(0);
        task.setTraceId(traceId);
        save(task);
    }

    private ResearchTask requireTask(String workspaceId, String taskId) {
        ResearchTask task = mapper.findByIdAndWorkspace(taskId, workspaceId);
        if (task == null) {
            throw BusinessException.notFound("task not found");
        }
        return task;
    }

    private void markFailedSync(String taskId, String workspaceId, String code, String message) {
        transactionTemplate.executeWithoutResult(tx -> {
            advance(taskId, workspaceId, TaskStatus.RUNNING, TaskStatus.FAILED, 30, null);
            mapper.updateTaskFinished(
                    taskId, workspaceId, TaskStatus.FAILED.name(), null, code, truncate(message, 1024));
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
        mapper.updateStatus(taskId, workspaceId, to.name(), progress, node);
    }

    private void insertEvents(String taskId, List<AgentEventDto> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        for (int i = 0; i < events.size(); i++) {
            AgentEventDto event = events.get(i);
            long eventNo = event.getEventId() != null ? event.getEventId() : (i + 1L);
            insertEventIgnoreDuplicate(taskId, eventNo, event);
        }
    }

    /** 插入单条事件；uk 冲突时忽略 */
    private void insertEventIgnoreDuplicate(String taskId, long eventNo, AgentEventDto event) {
        String payload;
        try {
            Map<String, Object> data = event.getData() == null ? Map.of() : event.getData();
            payload = objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            payload = "{}";
        }
        taskEventMapper.insertIgnore(
                taskId,
                eventNo,
                event.getRunId(),
                event.getNode(),
                event.getType(),
                payload,
                parseTs(event.getTimestamp()));
    }

    private void insertReport(
            String reportId, String taskId, String workspaceId, String markdown, String title) {
        Report report = new Report();
        report.setId(reportId);
        report.setTaskId(taskId);
        report.setWorkspaceId(workspaceId);
        report.setVersion(1);
        report.setTitle(title);
        report.setMarkdownContent(markdown);
        report.setStatus("READY");
        reportMapper.insert(report);
    }

    private TaskSummaryResponse toSummary(ResearchTask row) {
        TaskSummaryResponse r = new TaskSummaryResponse();
        r.setTaskId(row.getId());
        r.setWorkspaceId(row.getWorkspaceId());
        r.setCreatorId(row.getCreatorId());
        r.setQuery(row.getQuery());
        r.setStatus(row.getStatus());
        r.setProgress(row.getProgress() == null ? 0 : row.getProgress());
        r.setTraceId(row.getTraceId());
        r.setRunId(row.getCurrentRunId());
        r.setErrorCode(row.getErrorCode());
        r.setErrorMessage(row.getErrorMessage());
        if (row.getCreatedAt() != null) {
            r.setCreatedAt(Timestamp.valueOf(row.getCreatedAt()));
        }
        return r;
    }

    private static LocalDateTime parseTs(String iso) {
        if (iso == null || iso.isBlank()) {
            return LocalDateTime.now(ZoneOffset.UTC);
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(iso), ZoneOffset.UTC);
        } catch (Exception ex) {
            return LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
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
