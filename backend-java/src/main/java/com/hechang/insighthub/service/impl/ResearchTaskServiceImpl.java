package com.hechang.insighthub.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import com.hechang.insighthub.model.dto.task.*;
import com.hechang.insighthub.service.PlanApplicationService;
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
import com.hechang.insighthub.mapper.CitationMapper;
import com.hechang.insighthub.mapper.ResearchTaskMapper;
import com.hechang.insighthub.mapper.ReportMapper;
import com.hechang.insighthub.mapper.TaskDispatchOutboxMapper;
import com.hechang.insighthub.mapper.TaskEventMapper;
import com.hechang.insighthub.mapper.TaskPlanRevisionMapper;
import com.hechang.insighthub.model.dto.knowledge.CitationResponse;
import com.hechang.insighthub.model.entity.KnowledgeBase;
import com.hechang.insighthub.model.entity.ResearchTask;
import com.hechang.insighthub.model.enums.TaskStatus;
import com.hechang.insighthub.model.enums.QualityStatus;
import com.hechang.insighthub.model.enums.WorkspaceRole;
import com.hechang.insighthub.redis.TaskControlRedis;
import com.hechang.insighthub.redis.TaskCreateRateLimiter;
import com.hechang.insighthub.redis.TaskSlotTracker;
import com.hechang.insighthub.redis.WorkspaceConcurrencyService;
import com.hechang.insighthub.service.ResearchTaskService;
import com.hechang.insighthub.service.ResearchTaskQueryService;
import com.hechang.insighthub.service.TaskExecutionService;
import com.hechang.insighthub.service.TaskResultService;
import com.hechang.insighthub.service.WorkspaceAccessService;
import com.hechang.insighthub.service.CurrentWorkspaceAccess;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;

/**
 * 研究任务：异步流式 + 同步兼容 + 控制面实现。
 */
@Service
@RequiredArgsConstructor
public class ResearchTaskServiceImpl extends ServiceImpl<ResearchTaskMapper, ResearchTask>
        implements ResearchTaskService {

    private static final Logger log = LoggerFactory.getLogger(ResearchTaskServiceImpl.class);

    private final AgentServiceClient agentServiceClient;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final CitationMapper citationMapper;
    private final ReportMapper reportMapper;
    private final TaskEventMapper taskEventMapper;
    private final TaskDispatchOutboxMapper taskDispatchOutboxMapper;
    private final TaskPlanRevisionMapper taskPlanRevisionMapper;
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
    private final PlanApplicationService planApplicationService;

    @Override
    public CreateTaskAcceptedResponse createAsync(String workspaceId, CreateResearchTaskRequest request) {
        String userId = accessService.requireCurrentMember(workspaceId).userId();
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
            insertCreatedTask(taskId, workspaceId, userId, query, traceId, kbIds, request.isEnableDataAnalysis());
            advance(taskId, workspaceId, TaskStatus.CREATED, TaskStatus.PLANNING, 10, "create_plan");
            taskControlRedis.setControl(taskId, TaskControlRedis.CONTROL_RUNNING, ttl);
            taskExecutionService.executeStream(taskId, workspaceId, userId, query, traceId, false, null, 1);
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
        return new CreateTaskAcceptedResponse(taskId, TaskStatus.PLANNING.name(), traceId);
    }

    @Override
    public AgentTaskResponseDto createAndRun(String workspaceId, CreateResearchTaskRequest request) {
        String userId = accessService.requireCurrentMember(workspaceId).userId();
        String query = request.getQuery();
        List<String> kbIds = normalizeKbIds(request.getKnowledgeBaseIds());
        validateKnowledgeBases(workspaceId, kbIds);
        rateLimiter.acquire(userId);

        String taskId = "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String traceId = "trace-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        int ttl = taskProperties.getDefaultTimeoutSeconds() + 600;
        String permitId = concurrencyService.tryAcquire(workspaceId, ttl);

        try {
            insertCreatedTask(taskId, workspaceId, userId, query, traceId, kbIds, request.isEnableDataAnalysis());
            advance(taskId, workspaceId, TaskStatus.CREATED, TaskStatus.PLANNING, 10, "create_plan");

            AgentTaskResponseDto response;
            try {
                response = agentServiceClient.createTask(taskId, workspaceId, userId, query, traceId, kbIds,
                        request.isEnableDataAnalysis());
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
                    insertEvents(taskId, response.getEvents());
                    if (TaskStatus.COMPLETED.matches(finalStatus)) {
                        if (response.getReportMarkdown() == null || response.getReportMarkdown().isBlank()) {
                            throw new IllegalStateException("completed task has no report");
                        }
                        advance(taskId, workspaceId, TaskStatus.PLANNING, TaskStatus.RUNNING, 30, "dispatch_tasks");
                        advance(taskId, workspaceId, TaskStatus.RUNNING, TaskStatus.GENERATING, 80, "write_report");
                        taskResultService.saveReportAndCitations(
                                taskId, workspaceId, response.getReportMarkdown(), response.getCitations(), response.getQuality());
                        advance(taskId, workspaceId, TaskStatus.GENERATING, TaskStatus.COMPLETED, 100, "finalize");
                        mapper.updateTaskFinished(
                                taskId, workspaceId, TaskStatus.COMPLETED.name(), response.getRunId(), null, null);
                    } else if (TaskStatus.WAITING_APPROVAL.matches(finalStatus)) {
                        AgentEventDto planCreated = response.getEvents().stream()
                                .filter(event -> "PLAN_CREATED".equals(event.getType()))
                                .findFirst()
                                .orElseThrow(() -> new IllegalStateException("waiting approval response has no PLAN_CREATED event"));
                        planApplicationService.recordPlannerResult(
                                taskId,
                                workspaceId,
                                userId,
                                response.getRunId(),
                                planCreated.getData());
                    } else {
                        advance(taskId, workspaceId, TaskStatus.PLANNING, TaskStatus.FAILED, 30, null);
                        mapper.updateTaskFinished(
                                taskId, workspaceId, TaskStatus.FAILED.name(), response.getRunId(),
                                finalErrorCode, truncate(finalErrorMessage, 1024));
                    }
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
    public void delete(String workspaceId, String taskId) {
        CurrentWorkspaceAccess actor = accessService.requireCurrentMember(workspaceId);
        requireControllableTask(workspaceId, taskId, actor);
        transactionTemplate.executeWithoutResult(tx -> {
            ResearchTask locked = requireTaskForUpdate(workspaceId, taskId);
            if (!TaskStatus.isTerminal(locked.getStatus())) {
                throw BusinessException.conflict("TASK_NOT_TERMINAL", "only completed, failed, or cancelled tasks may be deleted");
            }
            citationMapper.deleteByTaskId(taskId);
            reportMapper.deleteByTaskId(taskId);
            taskEventMapper.deleteByTaskId(taskId);
            taskDispatchOutboxMapper.deleteByTaskId(taskId);
            // 必须先断开 FK，再删 task_plan_revision，否则 MySQL 拒绝删除被引用的修订行
            mapper.clearCurrentPlanRevision(taskId, workspaceId);
            taskPlanRevisionMapper.deleteByTaskId(taskId);
            mapper.deleteCheckpointsByTaskId(taskId);
            if (mapper.deleteByIdAndWorkspace(taskId, workspaceId) != 1) {
                throw BusinessException.conflict("TASK_STATE_CHANGED", "task changed while deleting");
            }
        });
        sseHub.completeTask(taskId);
    }

    @Override
    public ReportResponse getReport(String workspaceId, String taskId) {
        return taskQueryService.getReport(workspaceId, taskId);
    }

    @Override
    public List<ReportVersionResponse> listReportVersions(String workspaceId, String taskId) {
        return taskQueryService.listReportVersions(workspaceId, taskId);
    }

    @Override
    public ReportResponse getReportVersion(String workspaceId, String taskId, int version) {
        return taskQueryService.getReportVersion(workspaceId, taskId, version);
    }

    @Override
    public List<CitationResponse> listCitations(String workspaceId, String taskId) {
        return taskQueryService.listCitations(workspaceId, taskId);
    }

    @Override
    public List<CitationResponse> listCitations(String workspaceId, String taskId, int version) {
        return taskQueryService.listCitations(workspaceId, taskId, version);
    }

    @Override
    public List<TaskEventResponse> listEvents(String workspaceId, String taskId, long fromEventNo) {
        return taskQueryService.listEvents(workspaceId, taskId, fromEventNo);
    }

    @Override
    public SseEmitter streamEvents(String workspaceId, String taskId, long fromEventNo) {
        accessService.requireCurrentMember(workspaceId);
        return sseHub.subscribe(taskId, workspaceId, fromEventNo);
    }

    @Override
    public TaskControlResponse pause(String workspaceId, String taskId) {
        requireControllableTask(workspaceId, taskId, accessService.requireCurrentMember(workspaceId));
        transactionTemplate.executeWithoutResult(tx -> {
            ResearchTask locked = requireTaskForUpdate(workspaceId, taskId);
            stateMachine.transition(TaskStatus.valueOf(locked.getStatus()), TaskStatus.PAUSING);
            moveStatusIfCurrent(
                    taskId, workspaceId, TaskStatus.RUNNING, TaskStatus.PAUSING, null, null, "pausing");
        });
        taskControlRedis.setControl(
                taskId, TaskControlRedis.CONTROL_PAUSED, taskProperties.getDefaultTimeoutSeconds() + 600);
        return new TaskControlResponse(taskId, TaskStatus.PAUSING.name());
    }

    @Override
    public TaskControlResponse resume(String workspaceId, String taskId) {
        ResearchTask row = requireControllableTask(
                workspaceId, taskId, accessService.requireCurrentMember(workspaceId));
        stateMachine.transition(TaskStatus.valueOf(row.getStatus()), TaskStatus.RUNNING);
        int ttl = taskProperties.getDefaultTimeoutSeconds() + 600;
        String permitId = concurrencyService.tryAcquire(workspaceId, ttl);
        try {
            transactionTemplate.executeWithoutResult(tx -> {
                ResearchTask locked = requireTaskForUpdate(workspaceId, taskId);
                TaskStatus fromStatus = TaskStatus.valueOf(locked.getStatus());
                boolean qualityRetry = fromStatus == TaskStatus.COMPLETED
                        && ("FAIL".equals(locked.getQualityStatus()) || "LEGACY_SYNTHETIC".equals(locked.getQualityStatus()));
                if (fromStatus != TaskStatus.FAILED && !qualityRetry) {
                    throw BusinessException.conflict("TASK_NOT_RETRYABLE", "task has no failed quality result to retry");
                }
                stateMachine.transition(fromStatus, TaskStatus.RUNNING);
                moveStatusIfCurrent(
                        taskId, workspaceId, TaskStatus.PAUSED, TaskStatus.RUNNING, null, null, "resuming");
            });
        } catch (RuntimeException ex) {
            releaseTaskSlot(taskId, workspaceId);
            throw ex;
        }
        streamLease.invalidate(taskId);
        taskControlRedis.setControl(taskId, TaskControlRedis.CONTROL_RUNNING, ttl);
        try {
            taskExecutionService.executeStream(
                    taskId, workspaceId, row.getCreatorId(), row.getQuery(), row.getTraceId(), true,
                    row.getCurrentRunId(), 1);
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
        requireControllableTask(workspaceId, taskId, accessService.requireCurrentMember(workspaceId));
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
        CurrentWorkspaceAccess actor = accessService.requireCurrentMember(workspaceId);
        ResearchTask row = requireControllableTask(workspaceId, taskId, actor);
        rateLimiter.acquire(actor.userId());
        int ttl = taskProperties.getDefaultTimeoutSeconds() + 600;
        String permitId = concurrencyService.tryAcquire(workspaceId, ttl);
        String runId = "run-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Integer nextPlanRevision;
        try {
            slotTracker.markHeld(taskId, workspaceId, permitId, ttl);
            nextPlanRevision = transactionTemplate.execute(tx -> {
                ResearchTask locked = requireTaskForUpdate(workspaceId, taskId);
                TaskStatus fromStatus = TaskStatus.valueOf(locked.getStatus());
                boolean qualityRetry = fromStatus == TaskStatus.COMPLETED
                        && ("FAIL".equals(locked.getQualityStatus())
                        || "LEGACY_SYNTHETIC".equals(locked.getQualityStatus()));
                if (fromStatus != TaskStatus.FAILED && !qualityRetry) {
                    throw BusinessException.conflict(
                            "TASK_NOT_RETRYABLE", "task has no failed quality result to retry");
                }
                int revision = nextPlanRevisionNo(taskId);
                stateMachine.transition(fromStatus, TaskStatus.RUNNING);
                mapper.prepareRetry(taskId, workspaceId, runId);
                moveStatusIfCurrent(
                        taskId, workspaceId, fromStatus, TaskStatus.RUNNING, 30, "retry", "retrying");
                return revision;
            });
        } catch (RuntimeException ex) {
            releaseTaskSlot(taskId, workspaceId);
            throw ex;
        }
        taskControlRedis.setControl(taskId, TaskControlRedis.CONTROL_RUNNING, ttl);
        try {
            // 全量 stream 重跑同 taskId（event_no 继续递增）
            taskExecutionService.executeStream(
                    taskId, workspaceId, row.getCreatorId(), row.getQuery(), row.getTraceId(), false,
                    runId, nextPlanRevision == null ? 1 : nextPlanRevision);
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
            List<String> knowledgeBaseIds, boolean enableDataAnalysis) {
        ResearchTask task = new ResearchTask();
        task.setId(taskId);
        task.setWorkspaceId(workspaceId);
        task.setCreatorId(creatorId);
        task.setQuery(query);
        task.setStatus(TaskStatus.CREATED.name());
        task.setProgress(0);
        task.setQualityStatus(QualityStatus.PENDING.name());
        task.setVerifiedCitationCount(0);
        task.setTotalCitationCount(0);
        task.setTraceId(traceId);
        task.setEnableDataAnalysis(enableDataAnalysis);
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
            KnowledgeBase kb = knowledgeBaseMapper.findByIdAndWorkspace(kbId, workspaceId);
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

    private int nextPlanRevisionNo(String taskId) {
        var latest = taskPlanRevisionMapper.findLatestByTask(taskId);
        return latest == null ? 1 : latest.getRevisionNo() + 1;
    }

    /** 任务控制仅允许创建者或工作空间管理员。 */
    private ResearchTask requireControllableTask(
            String workspaceId, String taskId, CurrentWorkspaceAccess actor) {
        ResearchTask task = requireTask(workspaceId, taskId);
        if (!actor.userId().equals(task.getCreatorId()) && !actor.role().isAdminOrAbove()) {
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
        moveStatusIfCurrent(taskId, workspaceId, from, to, progress, node, "advancing");
    }

    /** CAS 迁状态；影响行数不为 1 说明并发覆盖。 */
    private void moveStatusIfCurrent(
            String taskId,
            String workspaceId,
            TaskStatus from,
            TaskStatus to,
            Integer progress,
            String node,
            String action) {
        int updated = mapper.updateStatusIfCurrent(
                taskId, workspaceId, from.name(), to.name(), progress, node);
        if (updated != 1) {
            throw BusinessException.conflict(
                    "TASK_STATE_CHANGED", "task status changed while " + action);
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

    @Override
    public PlanRevisionResponse getCurrentPlan(String workspaceId, String taskId) {
        return planApplicationService.current(workspaceId, taskId);
    }

    @Override
    public List<PlanRevisionResponse> listPlanHistory(String workspaceId, String taskId) {
        return planApplicationService.history(workspaceId, taskId);
    }

    @Override
    public PlanActionResponse approvePlan(String workspaceId, String taskId, ApprovePlanRequest request, String ip) {
        return planApplicationService.approve(workspaceId, taskId, request, ip);
    }

    @Override
    public PlanActionResponse revisePlan(String workspaceId, String taskId, RevisePlanRequest request, String ip) {
        return planApplicationService.revise(workspaceId, taskId, request, ip);
    }
}
