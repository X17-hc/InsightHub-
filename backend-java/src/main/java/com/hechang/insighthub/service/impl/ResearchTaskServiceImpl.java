package com.hechang.insighthub.service.impl;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import com.hechang.insighthub.mapper.CitationMapper;
import com.hechang.insighthub.mapper.KnowledgeBaseMapper;
import com.hechang.insighthub.mapper.ReportMapper;
import com.hechang.insighthub.mapper.ResearchTaskMapper;
import com.hechang.insighthub.mapper.TaskEventMapper;
import com.hechang.insighthub.model.dto.knowledge.CitationResponse;
import com.hechang.insighthub.model.dto.task.AgentEventDto;
import com.hechang.insighthub.model.dto.task.AgentTaskResponseDto;
import com.hechang.insighthub.model.dto.task.CreateResearchTaskRequest;
import com.hechang.insighthub.model.dto.task.CreateTaskAcceptedResponse;
import com.hechang.insighthub.model.dto.task.TaskControlResponse;
import com.hechang.insighthub.model.dto.task.TaskSummaryResponse;
import com.hechang.insighthub.model.entity.Citation;
import com.hechang.insighthub.model.entity.KnowledgeBase;
import com.hechang.insighthub.model.entity.Report;
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
    private final TaskEventMapper taskEventMapper;
    private final ReportMapper reportMapper;
    private final CitationMapper citationMapper;
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

    public ResearchTaskServiceImpl(
            AgentServiceClient agentServiceClient,
            TaskEventMapper taskEventMapper,
            ReportMapper reportMapper,
            CitationMapper citationMapper,
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
            ObjectMapper objectMapper) {
        this.agentServiceClient = agentServiceClient;
        this.taskEventMapper = taskEventMapper;
        this.reportMapper = reportMapper;
        this.citationMapper = citationMapper;
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
        if (permitId != null) {
            slotTracker.markHeld(taskId, workspaceId, permitId, ttl);
        }

        try {
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
                    if ("COMPLETED".equalsIgnoreCase(finalStatus)) {
                        if (response.getReportMarkdown() == null || response.getReportMarkdown().isBlank()) {
                            throw new IllegalStateException("completed task has no report");
                        }
                        advance(taskId, workspaceId, TaskStatus.RUNNING, TaskStatus.GENERATING, 80, "write_report");
                        String reportId = "report-"
                                + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                        insertReport(reportId, taskId, workspaceId, response.getReportMarkdown(),
                                extractTitle(response.getReportMarkdown()));
                        insertCitations(reportId, taskId, response.getCitations());
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
    public List<CitationResponse> listCitations(String workspaceId, String taskId) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);
        requireTask(workspaceId, taskId);
        return citationMapper.listByTaskId(taskId).stream().map(this::toCitationResponse).toList();
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
        if (permitId != null) {
            slotTracker.markHeld(taskId, workspaceId, permitId, ttl);
        }
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
        StoredTaskEvent cancelled = transactionTemplate.execute(tx -> {
            ResearchTask row = requireTaskForUpdate(workspaceId, taskId);
            TaskStatus from = TaskStatus.valueOf(row.getStatus());
            stateMachine.transition(from, TaskStatus.CANCELLED);
            mapper.updateTaskFinished(
                    taskId, workspaceId, TaskStatus.CANCELLED.name(), row.getCurrentRunId(),
                    "CANCELLED", truncate("cancelled by user", 1024));
            return insertTerminalEvent(
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

    private void insertCitations(String reportId, String taskId, List<Map<String, Object>> citations) {
        citationMapper.deleteByTaskId(taskId);
        if (citations == null || citations.isEmpty()) {
            return;
        }
        int i = 0;
        for (Map<String, Object> c : citations) {
            i++;
            Citation citation = new Citation();
            citation.setId("cit-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            citation.setReportId(reportId);
            citation.setTaskId(taskId);
            Object noObj = c.get("citationNo");
            int no = noObj instanceof Number n ? n.intValue() : i;
            citation.setCitationNo(no);
            citation.setSourceTitle(str(c.get("sourceTitle")));
            citation.setSourceUri(str(c.get("sourceUri")));
            citation.setSourceType(str(c.get("sourceType")));
            citation.setDocumentId(str(c.get("documentId")));
            citation.setChunkId(str(c.get("chunkId")));
            citation.setQuotedText(str(c.get("quotedText")));
            citation.setVerified(Boolean.TRUE.equals(c.get("verified")) ? 1 : 0);
            citationMapper.insert(citation);
        }
    }

    private CitationResponse toCitationResponse(Citation c) {
        return new CitationResponse(
                c.getId(),
                c.getReportId(),
                c.getTaskId(),
                c.getCitationNo(),
                c.getSourceTitle(),
                c.getSourceUri(),
                c.getSourceType(),
                c.getDocumentId(),
                c.getChunkId(),
                c.getQuotedText(),
                c.getVerified(),
                c.getCreatedAt());
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
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

    /** 在终态事务中插入可回放的标准 TASK_RESULT。 */
    private StoredTaskEvent insertTerminalEvent(
            String taskId,
            String runId,
            String status,
            Map<String, Object> error) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status);
        data.put("error", error);
        data.put("hasReport", false);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("serialize terminal event failed", ex);
        }

        long firstEventNo = taskEventMapper.maxEventNo(taskId) + 1;
        String timestamp = Instant.now().toString();
        for (int attempt = 0; attempt < 5; attempt++) {
            long eventNo = firstEventNo + attempt;
            int inserted = taskEventMapper.insertIgnore(
                    taskId,
                    eventNo,
                    runId,
                    null,
                    "TASK_RESULT",
                    payload,
                    parseTs(timestamp));
            if (inserted > 0) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("eventId", eventNo);
                body.put("taskId", taskId);
                body.put("runId", runId);
                body.put("node", null);
                body.put("type", "TASK_RESULT");
                body.put("timestamp", timestamp);
                body.put("data", data);
                try {
                    return new StoredTaskEvent(eventNo, objectMapper.writeValueAsString(body));
                } catch (JsonProcessingException ex) {
                    throw new IllegalStateException("serialize terminal event envelope failed", ex);
                }
            }
        }
        throw new IllegalStateException("unable to allocate terminal event number");
    }

    private void publishStoredEvent(String taskId, StoredTaskEvent event) {
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
            try {
                stateMachine.transition(TaskStatus.valueOf(row.getStatus()), TaskStatus.FAILED);
            } catch (Exception ignored) {
                // 初始化中断等异常也必须收敛到 FAILED
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

    private static boolean isTerminal(String status) {
        return TaskStatus.COMPLETED.name().equalsIgnoreCase(status)
                || TaskStatus.FAILED.name().equalsIgnoreCase(status)
                || TaskStatus.CANCELLED.name().equalsIgnoreCase(status);
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

    private record StoredTaskEvent(long eventNo, String json) {
    }
}
