package com.hechang.insighthub.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import com.hechang.insighthub.mapper.KnowledgeBaseMapper;
import com.hechang.insighthub.mapper.ResearchTaskMapper;
import com.hechang.insighthub.model.dto.knowledge.CitationResponse;
import com.hechang.insighthub.model.dto.task.AgentEventDto;
import com.hechang.insighthub.model.dto.task.AgentTaskResponseDto;
import com.hechang.insighthub.model.dto.task.CreateResearchTaskRequest;
import com.hechang.insighthub.model.dto.task.CreateTaskAcceptedResponse;
import com.hechang.insighthub.model.dto.task.ReportResponse;
import com.hechang.insighthub.model.dto.task.TaskControlResponse;
import com.hechang.insighthub.model.dto.task.TaskEventResponse;
import com.hechang.insighthub.model.dto.task.TaskSummaryResponse;
import com.hechang.insighthub.model.entity.KnowledgeBase;
import com.mybatisflex.core.query.QueryWrapper;
import com.hechang.insighthub.model.entity.ResearchTask;
import com.hechang.insighthub.model.enums.TaskStatus;
import com.hechang.insighthub.model.enums.WorkspaceRole;
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
    private final KnowledgeBaseMapper knowledgeBaseMapper;
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
    private final TaskResultService taskResultService;
    private final TaskEventService taskEventService;
    private final ResearchTaskQueryService taskQueryService;

    public ResearchTaskServiceImpl(
            AgentServiceClient agentServiceClient,
            KnowledgeBaseMapper knowledgeBaseMapper,
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
            ObjectMapper objectMapper,
            TaskResultService taskResultService,
            TaskEventService taskEventService,
            ResearchTaskQueryService taskQueryService) {
        this.agentServiceClient = agentServiceClient;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
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
        this.taskResultService = taskResultService;
        this.taskEventService = taskEventService;
        this.taskQueryService = taskQueryService;
    }

    @Override
    public CreateTaskAcceptedResponse createAsync(String workspaceId, CreateResearchTaskRequest request) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);
        String query = request.getQuery();
        List<String> kbIds = normalizeKbIds(request.getKnowledgeBaseIds());
        validateKnowledgeBases(workspaceId, kbIds);
        rateLimiter.acquire(userId);
        String taskId = "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String traceId = "trace-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        int ttl = taskProperties.getDefaultTimeoutSeconds() + 600;
        String permitId = concurrencyService.tryAcquire(workspaceId, ttl);

        try {
            slotTracker.markHeld(taskId, workspaceId, permitId, ttl);
            insertCreatedTask(taskId, workspaceId, userId, query, traceId, kbIds);
            advance(taskId, workspaceId, TaskStatus.CREATED, TaskStatus.PLANNING, 10, "create_plan");
            advance(taskId, workspaceId, TaskStatus.PLANNING, TaskStatus.RUNNING, 30, "dispatch_tasks");
            taskControlRedis.setControl(taskId, TaskControlRedis.CONTROL_RUNNING, ttl);
            taskExecutionService.executeStream(taskId, workspaceId, userId, query, traceId, false);
        } catch (RejectedExecutionException ex) {
            markFailedSync(taskId, workspaceId, "EXECUTOR_REJECTED", "task executor queue full");
            releaseTaskSlot(taskId, workspaceId);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "EXECUTOR_REJECTED: task executor busy");
        } catch (RuntimeException ex) {
            markFailedSync(taskId, workspaceId, "TASK_DISPATCH_FAILED", ex.getMessage());
            releaseTaskSlot(taskId, workspaceId);
            throw ex;
        }

        log.info("Async research task {} workspace={} traceId={}", taskId, workspaceId, traceId);
        return new CreateTaskAcceptedResponse(taskId, TaskStatus.RUNNING.name(), traceId);
    }

    @Override
    public AgentTaskResponseDto createAndRun(String workspaceId, CreateResearchTaskRequest request) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);
        String query = request.getQuery();
        List<String> kbIds = normalizeKbIds(request.getKnowledgeBaseIds());
        validateKnowledgeBases(workspaceId, kbIds);
        rateLimiter.acquire(userId);

        String taskId = "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String traceId = "trace-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        int ttl = taskProperties.getDefaultTimeoutSeconds() + 600;
        String permitId = concurrencyService.tryAcquire(workspaceId, ttl);

        try {
            insertCreatedTask(taskId, workspaceId, userId, query, traceId, kbIds);
            advance(taskId, workspaceId, TaskStatus.CREATED, TaskStatus.PLANNING, 10, "create_plan");
            advance(taskId, workspaceId, TaskStatus.PLANNING, TaskStatus.RUNNING, 30, "dispatch_tasks");

            AgentTaskResponseDto response;
            try {
                response = agentServiceClient.createTask(taskId, workspaceId, userId, query, traceId, kbIds);
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
            try {
                transactionTemplate.executeWithoutResult(tx -> {
                    if (TaskStatus.COMPLETED.matches(finalStatus)) {
                        if (response.getReportMarkdown() == null || response.getReportMarkdown().isBlank()) {
                            throw new IllegalStateException("completed task has no report");
                        }
                        advance(taskId, workspaceId, TaskStatus.RUNNING, TaskStatus.GENERATING, 80, "write_report");
                        taskResultService.saveReportAndCitations(
                                taskId, workspaceId, response.getReportMarkdown(), response.getCitations());
                        advance(taskId, workspaceId, TaskStatus.GENERATING, TaskStatus.COMPLETED, 100, "finalize");
                        mapper.updateTaskFinished(
                                taskId, workspaceId, TaskStatus.COMPLETED.name(), response.getRunId(), null, null);
                    } else {
                        advance(taskId, workspaceId, TaskStatus.RUNNING, TaskStatus.FAILED, 30, null);
                        mapper.updateTaskFinished(
                                taskId, workspaceId, TaskStatus.FAILED.name(), response.getRunId(),
                                finalErrorCode, truncate(finalErrorMessage, 1024));
                    }
                    insertEvents(taskId, response.getEvents());
                });
            } catch (RuntimeException ex) {
                log.error("Persist agent result failed taskId={} workspace={}", taskId, workspaceId, ex);
                markFailedSync(
                        taskId, workspaceId, response.getRunId(),
                        "REPORT_PERSIST_FAILED", "persist agent result failed");
                throw new BusinessException(
                        ErrorCode.SYSTEM_ERROR, "REPORT_PERSIST_FAILED: persist agent result failed");
            }

            response.setTaskId(taskId);
            response.setStatus(status.toUpperCase());
            return response;
        } finally {
            concurrencyService.release(workspaceId, permitId);
        }
    }

    @Override
    public List<TaskSummaryResponse> list(String workspaceId) {
        return taskQueryService.list(workspaceId);
    }

    @Override
    public TaskSummaryResponse get(String workspaceId, String taskId) {
        return taskQueryService.get(workspaceId, taskId);
    }

    @Override
    public ReportResponse getReport(String workspaceId, String taskId) {
        return taskQueryService.getReport(workspaceId, taskId);
    }

    @Override
    public List<CitationResponse> listCitations(String workspaceId, String taskId) {
        return taskQueryService.listCitations(workspaceId, taskId);
    }

    @Override
    public List<TaskEventResponse> listEvents(String workspaceId, String taskId, long fromEventNo) {
        return taskQueryService.listEvents(workspaceId, taskId, fromEventNo);
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
        requireControllableTask(workspaceId, taskId, userId);
        transactionTemplate.executeWithoutResult(tx -> {
            ResearchTask locked = requireTaskForUpdate(workspaceId, taskId);
            stateMachine.transition(TaskStatus.valueOf(locked.getStatus()), TaskStatus.PAUSING);
            int updated = mapper.updateStatusIfCurrent(
                    taskId, workspaceId, TaskStatus.RUNNING.name(), TaskStatus.PAUSING.name(), null, null);
            if (updated != 1) {
                throw BusinessException.conflict("TASK_STATE_CHANGED", "task status changed while pausing");
            }
        });
        taskControlRedis.setControl(
                taskId, TaskControlRedis.CONTROL_PAUSED, taskProperties.getDefaultTimeoutSeconds() + 600);
        return new TaskControlResponse(taskId, TaskStatus.PAUSING.name());
    }

    @Override
    public TaskControlResponse resume(String workspaceId, String taskId) {
        String userId = SecurityUtils.requireUserId();
        ResearchTask row = requireControllableTask(workspaceId, taskId, userId);
        stateMachine.transition(TaskStatus.valueOf(row.getStatus()), TaskStatus.RUNNING);
        int ttl = taskProperties.getDefaultTimeoutSeconds() + 600;
        String permitId = concurrencyService.tryAcquire(workspaceId, ttl);
        try {
            transactionTemplate.executeWithoutResult(tx -> {
                ResearchTask locked = requireTaskForUpdate(workspaceId, taskId);
                stateMachine.transition(TaskStatus.valueOf(locked.getStatus()), TaskStatus.RUNNING);
                int updated = mapper.updateStatusIfCurrent(
                        taskId, workspaceId, TaskStatus.PAUSED.name(), TaskStatus.RUNNING.name(), null, null);
                if (updated != 1) {
                    throw BusinessException.conflict(
                            "TASK_STATE_CHANGED", "task status changed while resuming");
                }
            });
        } catch (RuntimeException ex) {
            releaseTaskSlot(taskId, workspaceId);
            throw ex;
        }
        streamLease.invalidate(taskId);
        taskControlRedis.setControl(taskId, TaskControlRedis.CONTROL_RUNNING, ttl);
        try {
            taskExecutionService.executeStream(
                    taskId, workspaceId, row.getCreatorId(), row.getQuery(), row.getTraceId(), true);
        } catch (RejectedExecutionException ex) {
            // 回滚为 PAUSED，避免无 worker 的 RUNNING 脏状态
            taskControlRedis.setControl(
                    taskId, TaskControlRedis.CONTROL_PAUSED, taskProperties.getDefaultTimeoutSeconds() + 600);
            mapper.updateStatusIfCurrent(
                    taskId, workspaceId, TaskStatus.RUNNING.name(), TaskStatus.PAUSED.name(), null, null);
            releaseTaskSlot(taskId, workspaceId);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "EXECUTOR_REJECTED: task executor busy");
        } catch (RuntimeException ex) {
            taskControlRedis.setControl(
                    taskId, TaskControlRedis.CONTROL_PAUSED, taskProperties.getDefaultTimeoutSeconds() + 600);
            mapper.updateStatusIfCurrent(
                    taskId, workspaceId, TaskStatus.RUNNING.name(), TaskStatus.PAUSED.name(), null, null);
            releaseTaskSlot(taskId, workspaceId);
            throw ex;
        }
        return new TaskControlResponse(taskId, TaskStatus.RUNNING.name());
    }

    @Override
    public TaskControlResponse cancel(String workspaceId, String taskId) {
        String userId = SecurityUtils.requireUserId();
        requireControllableTask(workspaceId, taskId, userId);
        TaskEventService.StoredEvent cancelled = transactionTemplate.execute(tx -> {
            ResearchTask row = requireTaskForUpdate(workspaceId, taskId);
            TaskStatus from = TaskStatus.valueOf(row.getStatus());
            stateMachine.transition(from, TaskStatus.CANCELLED);
            mapper.updateTaskFinished(
                    taskId, workspaceId, TaskStatus.CANCELLED.name(), row.getCurrentRunId(),
                    "CANCELLED", truncate("cancelled by user", 1024));
            return taskEventService.insertTerminalResult(
                    taskId,
                    row.getCurrentRunId(),
                    TaskStatus.CANCELLED.name(),
                    Map.of("code", "CANCELLED", "message", "cancelled by user"));
        });
        streamLease.invalidate(taskId);
        taskControlRedis.setControl(
                taskId, TaskControlRedis.CONTROL_CANCELLED, taskProperties.getDefaultTimeoutSeconds() + 600);
        publishStoredEvent(taskId, cancelled);
        releaseTaskSlot(taskId, workspaceId);
        sseHub.completeTask(taskId);
        return new TaskControlResponse(taskId, TaskStatus.CANCELLED.name());
    }

    @Override
    public CreateTaskAcceptedResponse retry(String workspaceId, String taskId) {
        String userId = SecurityUtils.requireUserId();
        ResearchTask row = requireControllableTask(workspaceId, taskId, userId);
        rateLimiter.acquire(userId);
        int ttl = taskProperties.getDefaultTimeoutSeconds() + 600;
        String permitId = concurrencyService.tryAcquire(workspaceId, ttl);
        if (permitId != null) {
            slotTracker.markHeld(taskId, workspaceId, permitId, ttl);
        }

        String runId = "run-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try {
            slotTracker.markHeld(taskId, workspaceId, permitId, ttl);
            transactionTemplate.executeWithoutResult(tx -> {
                ResearchTask locked = requireTaskForUpdate(workspaceId, taskId);
                stateMachine.transition(TaskStatus.valueOf(locked.getStatus()), TaskStatus.RUNNING);
                mapper.prepareRetry(taskId, workspaceId, runId);
                int updated = mapper.updateStatusIfCurrent(
                        taskId, workspaceId, TaskStatus.FAILED.name(), TaskStatus.RUNNING.name(), 30, "retry");
                if (updated != 1) {
                    throw BusinessException.conflict("TASK_STATE_CHANGED", "task status changed while retrying");
                }
            });
        } catch (RuntimeException ex) {
            releaseTaskSlot(taskId, workspaceId);
            throw ex;
        }
        taskControlRedis.setControl(taskId, TaskControlRedis.CONTROL_RUNNING, ttl);
        try {
            // 全量 stream 重跑同 taskId（event_no 继续递增）
            taskExecutionService.executeStream(
                    taskId, workspaceId, row.getCreatorId(), row.getQuery(), row.getTraceId(), false);
        } catch (RejectedExecutionException ex) {
            markFailedSync(
                    taskId, workspaceId, runId,
                    "EXECUTOR_REJECTED", "task executor queue full");
            releaseTaskSlot(taskId, workspaceId);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "EXECUTOR_REJECTED: task executor busy");
        } catch (RuntimeException ex) {
            markFailedSync(
                    taskId, workspaceId, runId,
                    "RETRY_SUBMIT_FAILED", ex.getMessage());
            releaseTaskSlot(taskId, workspaceId);
            throw ex;
        }
        return new CreateTaskAcceptedResponse(taskId, TaskStatus.RUNNING.name(), row.getTraceId());
    }

    /** 插入 CREATED 状态任务 */
    private void insertCreatedTask(
            String taskId,
            String workspaceId,
            String creatorId,
            String query,
            String traceId,
            List<String> knowledgeBaseIds) {
        ResearchTask task = new ResearchTask();
        task.setId(taskId);
        task.setWorkspaceId(workspaceId);
        task.setCreatorId(creatorId);
        task.setQuery(query);
        task.setStatus(TaskStatus.CREATED.name());
        task.setProgress(0);
        task.setTraceId(traceId);
        try {
            task.setKnowledgeBaseIds(objectMapper.writeValueAsString(
                    knowledgeBaseIds == null ? List.of() : knowledgeBaseIds));
        } catch (JsonProcessingException e) {
            task.setKnowledgeBaseIds("[]");
        }
        save(task);
    }

    /** 校验知识库均属于当前空间且 ACTIVE */
    private void validateKnowledgeBases(String workspaceId, List<String> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return;
        }
        for (String kbId : kbIds) {
            KnowledgeBase kb = knowledgeBaseMapper.selectOneByQuery(QueryWrapper.create()
                    .eq(KnowledgeBase::getId, kbId)
                    .eq(KnowledgeBase::getWorkspaceId, workspaceId));
            if (kb == null) {
                throw BusinessException.notFound("knowledge base not found: " + kbId);
            }
            if (!"ACTIVE".equalsIgnoreCase(kb.getStatus())) {
                throw BusinessException.badRequest("KB_DISABLED", "knowledge base not active: " + kbId);
            }
        }
    }

    private static List<String> normalizeKbIds(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String id : raw) {
            if (id != null && !id.isBlank() && !out.contains(id)) {
                out.add(id.trim());
            }
        }
        return out;
    }

    private ResearchTask requireTask(String workspaceId, String taskId) {
        ResearchTask task = mapper.findByIdAndWorkspace(taskId, workspaceId);
        if (task == null) {
            throw BusinessException.notFound("task not found");
        }
        return task;
    }

    private ResearchTask requireTaskForUpdate(String workspaceId, String taskId) {
        ResearchTask task = mapper.findByIdAndWorkspaceForUpdate(taskId, workspaceId);
        if (task == null) {
            throw BusinessException.notFound("task not found");
        }
        return task;
    }

    /** 任务控制仅允许创建者或工作空间管理员。 */
    private ResearchTask requireControllableTask(String workspaceId, String taskId, String userId) {
        WorkspaceRole role = accessService.requireMember(workspaceId, userId);
        ResearchTask task = requireTask(workspaceId, taskId);
        if (!userId.equals(task.getCreatorId()) && !role.isAdminOrAbove()) {
            throw BusinessException.forbidden("only task creator or workspace admin may control task");
        }
        return task;
    }

    private void releaseTaskSlot(String taskId, String workspaceId) {
        slotTracker.releaseOnce(
                taskId,
                workspaceId,
                permitId -> concurrencyService.release(workspaceId, permitId));
    }

    private void publishStoredEvent(String taskId, TaskEventService.StoredEvent event) {
        if (event == null) {
            return;
        }
        taskControlRedis.publishEvent(taskId, event.json());
        sseHub.broadcastLocal(taskId, event.eventNo(), "TASK_RESULT", event.json());
    }

    private void markFailedSync(String taskId, String workspaceId, String code, String message) {
        markFailedSync(taskId, workspaceId, null, code, message);
    }

    private void markFailedSync(
            String taskId, String workspaceId, String runId, String code, String message) {
        transactionTemplate.executeWithoutResult(tx -> {
            ResearchTask row = mapper.findByIdAndWorkspaceForUpdate(taskId, workspaceId);
            if (row == null || isTerminal(row.getStatus())) {
                return;
            }
            TaskStatus from = TaskStatus.tryParse(row.getStatus());
            if (from == null) {
                log.error("force failed task from invalid state taskId={} current={}", taskId, row.getStatus());
            } else if (from != TaskStatus.FAILED && stateMachine.canTransition(from, TaskStatus.FAILED)) {
                stateMachine.transition(from, TaskStatus.FAILED);
            } else if (from != TaskStatus.FAILED) {
                log.warn("force failed task bypassing state machine taskId={} from={}", taskId, from);
            }
            mapper.updateTaskFinished(
                    taskId, workspaceId, TaskStatus.FAILED.name(),
                    runId == null ? row.getCurrentRunId() : runId,
                    code, truncate(message, 1024));
        });
    }

    void advance(
            String taskId,
            String workspaceId,
            TaskStatus from,
            TaskStatus to,
            int progress,
            String node) {
        stateMachine.transition(from, to);
        int updated = mapper.updateStatusIfCurrent(
                taskId, workspaceId, from.name(), to.name(), progress, node);
        if (updated != 1) {
            throw BusinessException.conflict(
                    "TASK_STATE_CHANGED", "task status changed while advancing");
        }
    }

    private void insertEvents(String taskId, List<AgentEventDto> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        taskEventService.insertAll(taskId, events);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static boolean isTerminal(String status) {
        return TaskStatus.isTerminal(status);
    }

}
