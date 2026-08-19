package com.hechang.insighthub.service.impl;

import jakarta.annotation.Resource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hechang.insighthub.config.TaskProperties;
import com.hechang.insighthub.integration.AgentStreamClient;
import com.hechang.insighthub.mapper.ResearchTaskMapper;
import com.hechang.insighthub.mapper.TaskEventMapper;
import com.hechang.insighthub.model.dto.task.AgentEventDto;
import com.hechang.insighthub.model.entity.Citation;
import com.hechang.insighthub.model.entity.Report;
import com.hechang.insighthub.model.entity.ResearchTask;
import com.hechang.insighthub.model.enums.TaskStatus;
import com.hechang.insighthub.redis.TaskControlRedis;
import com.hechang.insighthub.redis.TaskSlotTracker;
import com.hechang.insighthub.redis.WorkspaceConcurrencyService;
import com.hechang.insighthub.service.TaskExecutionService;
import com.hechang.insighthub.service.PlanApplicationService;

/**
 * 异步消费 Python NDJSON 流并落库 / 推送实现。
 */
@Service
public class TaskExecutionServiceImpl implements TaskExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionServiceImpl.class);

    @Resource
    private AgentStreamClient agentStreamClient;
    @Resource
    private ResearchTaskMapper researchTaskMapper;
    @Resource
    private TaskEventMapper taskEventMapper;
    @Resource
    private TaskEventService taskEventService;
    @Resource
    private TaskResultService taskResultService;
    @Resource
    private TaskStateMachine stateMachine;
    @Resource
    private TaskControlRedis taskControlRedis;
    @Resource
    private WorkspaceConcurrencyService concurrencyService;
    @Resource
    private TaskSlotTracker slotTracker;
    @Resource
    private TaskEventSseHub sseHub;
    @Resource
    private TaskStreamLease streamLease;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private TaskProperties taskProperties;
    @Resource
    private TransactionTemplate transactionTemplate;
    @Resource
    private PlanApplicationService planApplicationService;

    @Override
    @Async("taskExecutor")
    public void executeStream(
            String taskId,
            String workspaceId,
            String userId,
            String query,
            String traceId,
            boolean resume) {
        executeStreamInternal(null, taskId, workspaceId, userId, query, traceId, resume);
    }

    @Override
    public void executeDispatch(com.hechang.insighthub.service.TaskDispatchCommand command) {
        executeStreamInternal(command, command.taskId(), command.workspaceId(), command.userId(),
                command.query(), command.traceId(), "EXECUTE".equals(command.phase()));
    }

    private void executeStreamInternal(
            com.hechang.insighthub.service.TaskDispatchCommand command,
            String taskId, String workspaceId, String userId, String query, String traceId, boolean resume) {
        String generation = streamLease.acquire(taskId);
        AtomicInteger badLines = new AtomicInteger();
        try {
            int timeout = taskProperties.getDefaultTimeoutSeconds();
            if (resume) {
                if (command != null && command.approvedPlanHash() != null) {
                    agentStreamClient.approvePlan(taskId, command.runId(), command.approvedPlanHash(), traceId, timeout,
                            node -> handleLine(taskId, workspaceId, node, badLines, generation));
                } else {
                    agentStreamClient.resumeTask(taskId, null, traceId, timeout,
                            node -> handleLine(taskId, workspaceId, node, badLines, generation));
                }
            } else {
                long nextEventId = taskEventService.maxEventNo(taskId) + 1;
                List<String> kbIds = parseKbIds(
                        researchTaskMapper.findByIdAndWorkspace(taskId, workspaceId));
                if (command != null) {
                    agentStreamClient.streamTask(command, timeout, nextEventId <= 1 ? null : nextEventId,
                            node -> handleLine(taskId, workspaceId, node, badLines, generation));
                } else {
                    String idem = taskId + "-stream-" + System.currentTimeMillis();
                    agentStreamClient.streamTask(taskId, workspaceId, userId, query, traceId, timeout,
                            nextEventId <= 1 ? null : nextEventId, idem, kbIds,
                            node -> handleLine(taskId, workspaceId, node, badLines, generation));
                }
            }
            if (!streamLease.isCurrent(taskId, generation)) {
                return;
            }
            // 若流结束仍非终态（异常静默），检查 DB
            ResearchTask row = researchTaskMapper.findByIdAndWorkspace(taskId, workspaceId);
            if (row != null && !isTerminal(row.getStatus())
                    && !TaskStatus.PAUSED.matches(row.getStatus())
                    && !TaskStatus.WAITING_APPROVAL.matches(row.getStatus())) {
                markFailed(taskId, workspaceId, null, "AGENT_STREAM_INCOMPLETE", "stream ended without result");
            }
        } catch (Exception ex) {
            if (!streamLease.isCurrent(taskId, generation)) {
                log.info("ignore stream error after lease invalidate taskId={}", taskId);
                return;
            }
            log.error("executeStream failed taskId={}", taskId, ex);
            markFailed(taskId, workspaceId, null, "AGENT_STREAM_FAILED", ex.getMessage());
            taskControlRedis.setControl(
                    taskId,
                    TaskControlRedis.CONTROL_CANCELLED,
                    taskProperties.getDefaultTimeoutSeconds() + 600);
        } finally {
            streamLease.release(taskId, generation);
            ResearchTask row = researchTaskMapper.findByIdAndWorkspace(taskId, workspaceId);
            if (row != null && (isTerminal(row.getStatus())
                    || TaskStatus.PAUSED.matches(row.getStatus())
                    || TaskStatus.WAITING_APPROVAL.matches(row.getStatus()))) {
                slotTracker.releaseOnce(
                        taskId,
                        workspaceId,
                        permitId -> concurrencyService.release(workspaceId, permitId));
                if (isTerminal(row.getStatus())) {
                    sseHub.completeTask(taskId);
                }
            }
        }
    }

    private void handleLine(
            String taskId,
            String workspaceId,
            JsonNode node,
            AtomicInteger badLines,
            String generation) {
        if (!streamLease.isCurrent(taskId, generation)) {
            return;
        }
        if (node == null || node.isNull()) {
            return;
        }
        String type = text(node, "type");
        if ("TASK_RESULT".equals(type)) {
            AtomicReference<PublishedTaskResult> published = new AtomicReference<>();
            transactionTemplate.executeWithoutResult(tx -> {
                if (finalizeResult(taskId, workspaceId, node)) {
                    published.set(persistTaskResult(taskId, node));
                }
            });
            if (published.get() != null) {
                if (TaskStatus.PAUSED.matches(text(node, "status"))
                        || TaskStatus.WAITING_APPROVAL.matches(text(node, "status"))) {
                    slotTracker.releaseOnce(
                            taskId,
                            workspaceId,
                            permitId -> concurrencyService.release(workspaceId, permitId));
                }
                publishTaskResult(taskId, published.get());
            }
            return;
        }
        AgentEventDto event = taskEventService.toDto(node);
        if (event.getType() == null) {
            if (badLines.incrementAndGet() > 20) {
                throw new IllegalStateException("too many bad event lines");
            }
            return;
        }
        long eventNo = event.getEventId() == null ? 0L : event.getEventId();
        if (eventNo <= 0) {
            return;
        }
        taskEventService.insertIgnoreDuplicate(taskId, eventNo, event);
        try {
            String json = objectMapper.writeValueAsString(node);
            taskControlRedis.publishEvent(taskId, json);
            sseHub.broadcastLocal(taskId, eventNo, event.getType(), json);
        } catch (Exception ex) {
            log.warn("publish event failed taskId={} eventNo={}", taskId, eventNo, ex);
        }
        applySideEffects(taskId, workspaceId, event);
    }

    /** 将 TASK_RESULT 规范化后落库，实时与回放使用同一种结构。 */
    private PublishedTaskResult persistTaskResult(String taskId, JsonNode node) {
        try {
            long firstEventNo = taskEventService.maxEventNo(taskId) + 1;
            String status = text(node, "status");
            String runId = text(node, "runId");
            AgentEventDto dto = new AgentEventDto();
            dto.setTaskId(taskId);
            dto.setRunId(runId);
            dto.setType("TASK_RESULT");
            dto.setTimestamp(Instant.now().toString());
            Map<String, Object> data = new HashMap<>();
            data.put("status", status);
            if (node.has("error") && !node.get("error").isNull()) {
                data.put("error", objectMapper.convertValue(node.get("error"), Map.class));
            }
            // 报告可能很大，SSE/事件表只记是否有报告
            data.put("hasReport", node.has("reportMarkdown")
                    && !node.get("reportMarkdown").isNull()
                    && !node.get("reportMarkdown").asText("").isBlank());
            data.put("schemaVersion", "1.0");
            dto.setData(data);
            for (int attempt = 0; attempt < 5; attempt++) {
                long eventNo = firstEventNo + attempt;
                dto.setEventId(eventNo);
                if (taskEventService.insertIgnoreDuplicate(taskId, eventNo, dto) > 0) {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("eventId", eventNo);
                    body.put("taskId", taskId);
                    body.put("runId", runId);
                    body.put("node", null);
                    body.put("type", "TASK_RESULT");
                    body.put("timestamp", dto.getTimestamp());
                    body.put("data", data);
                    return new PublishedTaskResult(eventNo, objectMapper.writeValueAsString(body));
                }
            }
            throw new IllegalStateException("unable to allocate TASK_RESULT event number");
        } catch (Exception ex) {
            throw new IllegalStateException("persist TASK_RESULT failed", ex);
        }
    }

    private void publishTaskResult(String taskId, PublishedTaskResult result) {
        try {
            taskControlRedis.publishEvent(taskId, result.json());
            sseHub.broadcastLocal(taskId, result.eventNo(), "TASK_RESULT", result.json());
        } catch (Exception ex) {
            log.warn("publish TASK_RESULT failed taskId={}", taskId, ex);
        }
    }

    private void applySideEffects(String taskId, String workspaceId, AgentEventDto event) {
        String type = event.getType();
        String node = event.getNode();
        ResearchTask task = researchTaskMapper.findByIdAndWorkspace(taskId, workspaceId);
        String current = task == null ? null : task.getStatus();
        if (current == null || isTerminal(current)) {
            return;
        }
        boolean paused = TaskStatus.PAUSED.matches(current) || TaskStatus.PAUSING.matches(current);
        switch (type) {
            case "PLAN_CREATED" -> {
                if (!paused && (TaskStatus.PLANNING.matches(current) || TaskStatus.RUNNING.matches(current))) {
                    planApplicationService.recordPlannerResult(
                            taskId,
                            workspaceId,
                            task.getCreatorId(),
                            event.getRunId(),
                            event.getData());
                }
            }
            case "APPROVAL_REQUIRED" -> {
                if (TaskStatus.PLANNING.matches(current) || TaskStatus.RUNNING.matches(current)) {
                    researchTaskMapper.updateStatusIfCurrent(
                            taskId,
                            workspaceId,
                            current,
                            TaskStatus.WAITING_APPROVAL.name(),
                            30,
                            "wait_for_approval");
                }
            }
            case "NODE_STARTED", "NODE_COMPLETED" -> {
                if (!paused && TaskStatus.RUNNING.matches(current)) {
                    researchTaskMapper.updateStatusIfCurrent(
                            taskId, workspaceId, TaskStatus.RUNNING.name(),
                            TaskStatus.RUNNING.name(), null, node);
                }
            }
            case "CRITIC_STARTED" -> {
                if (!paused && TaskStatus.RUNNING.matches(current)) {
                    researchTaskMapper.updateStatusIfCurrent(
                            taskId, workspaceId, TaskStatus.RUNNING.name(),
                            TaskStatus.RUNNING.name(), 55, "critic_review");
                }
            }
            case "CRITIQUE_COMPLETED" -> {
                if (!paused && TaskStatus.RUNNING.matches(current)) {
                    researchTaskMapper.updateStatusIfCurrent(
                            taskId, workspaceId, TaskStatus.RUNNING.name(),
                            TaskStatus.RUNNING.name(), 60, "critic_review");
                }
            }
            case "SUPPLEMENT_RESEARCH_REQUESTED" -> {
                if (!paused && TaskStatus.RUNNING.matches(current)) {
                    researchTaskMapper.updateStatusIfCurrent(
                            taskId, workspaceId, TaskStatus.RUNNING.name(),
                            TaskStatus.RUNNING.name(), 65, "supplement_research");
                }
            }
            case "TASK_PAUSED" -> {
                if (TaskStatus.RUNNING.matches(current) || TaskStatus.PAUSING.matches(current)) {
                    stateMachine.transition(TaskStatus.valueOf(current), TaskStatus.PAUSED);
                    researchTaskMapper.updateStatusIfCurrent(
                            taskId, workspaceId, current, TaskStatus.PAUSED.name(), null, node);
                }
            }
            case "TASK_COMPLETED" -> {
                if (TaskStatus.RUNNING.matches(current) || TaskStatus.PAUSING.matches(current)) {
                    stateMachine.transition(TaskStatus.valueOf(current), TaskStatus.GENERATING);
                    researchTaskMapper.updateStatusIfCurrent(
                            taskId, workspaceId, current,
                            TaskStatus.GENERATING.name(), 80, "write_report");
                }
            }
            case "TASK_FAILED" -> {
                String code = null;
                String msg = null;
                if (event.getData() != null) {
                    code = stringOrNull(event.getData().get("code"));
                    msg = stringOrNull(event.getData().get("message"));
                }
                if (TaskStatus.CANCELLED.matches(code)) {
                    forceStatus(taskId, workspaceId, TaskStatus.CANCELLED, event.getRunId(), code, msg);
                }
            }
            default -> {
                // no-op
            }
        }
    }

    private boolean finalizeResult(String taskId, String workspaceId, JsonNode node) {
        String status = text(node, "status");
        String runId = text(node, "runId");
        String report = text(node, "reportMarkdown");
        JsonNode err = node.get("error");
        String errCode = err != null && !err.isNull() ? text(err, "code") : null;
        String errMsg = err != null && !err.isNull() ? text(err, "message") : null;

        ResearchTask row = researchTaskMapper.findByIdAndWorkspaceForUpdate(taskId, workspaceId);
        if (row == null) {
            return false;
        }
        String currentStatus = row.getStatus();
        if (isTerminal(currentStatus)) {
            log.info("ignore late TASK_RESULT status={} current={} taskId={}", status, currentStatus, taskId);
            return false;
        }

        if (TaskStatus.PAUSED.matches(status)) {
            if (TaskStatus.RUNNING.matches(currentStatus) || TaskStatus.PAUSING.matches(currentStatus)) {
                stateMachine.transition(TaskStatus.valueOf(currentStatus), TaskStatus.PAUSED);
                researchTaskMapper.updateStatus(taskId, workspaceId, TaskStatus.PAUSED.name(), null, null);
            }
            return true;
        }
        if (TaskStatus.WAITING_APPROVAL.matches(status)) {
            if (!TaskStatus.WAITING_APPROVAL.matches(currentStatus)) {
                researchTaskMapper.updateStatusIfCurrent(
                        taskId,
                        workspaceId,
                        currentStatus,
                        TaskStatus.WAITING_APPROVAL.name(),
                        30,
                        "wait_for_approval");
            }
            return true;
        }
        if (TaskStatus.COMPLETED.matches(status)) {
            TaskStatus cur = TaskStatus.valueOf(currentStatus);
            if (cur == TaskStatus.PAUSED) {
                log.info("ignore COMPLETED while PAUSED taskId={}", taskId);
                return false;
            }
            if (cur == TaskStatus.RUNNING || cur == TaskStatus.PAUSING) {
                stateMachine.transition(cur, TaskStatus.GENERATING);
                researchTaskMapper.updateStatus(
                        taskId, workspaceId, TaskStatus.GENERATING.name(), 80, "write_report");
                cur = TaskStatus.GENERATING;
            }
            if (cur == TaskStatus.GENERATING) {
                if (report == null || report.isBlank()) {
                    throw new IllegalStateException("completed task has no report");
                }
                taskResultService.saveReportAndCitations(taskId, workspaceId, report, node.get("citations"));
                stateMachine.transition(TaskStatus.GENERATING, TaskStatus.COMPLETED);
                researchTaskMapper.updateTaskFinished(
                        taskId, workspaceId, TaskStatus.COMPLETED.name(), runId, null, null);
            }
            return true;
        }
        if (TaskStatus.CANCELLED.matches(status)) {
            forceStatus(taskId, workspaceId, TaskStatus.CANCELLED, runId, errCode, errMsg);
            return true;
        }
        if (TaskStatus.CANCELLED.matches(currentStatus) || TaskStatus.COMPLETED.matches(currentStatus)) {
            return false;
        }
        forceStatus(taskId, workspaceId, TaskStatus.FAILED, runId, errCode, errMsg);
        return true;
    }

    private void forceStatus(
            String taskId,
            String workspaceId,
            TaskStatus to,
            String runId,
            String errCode,
            String errMsg) {
        transactionTemplate.executeWithoutResult(tx -> {
            ResearchTask row = researchTaskMapper.findByIdAndWorkspaceForUpdate(taskId, workspaceId);
            if (row == null || isTerminal(row.getStatus())) {
                return;
            }
            TaskStatus from = TaskStatus.tryParse(row.getStatus());
            if (from == null) {
                log.error("force terminal status from invalid state taskId={} current={} target={}",
                        taskId, row.getStatus(), to);
            } else if (from != to && stateMachine.canTransition(from, to)) {
                stateMachine.transition(from, to);
            } else if (from != to) {
                log.warn("force terminal status bypassing state machine taskId={} from={} to={}", taskId, from, to);
            }
            researchTaskMapper.updateTaskFinished(
                    taskId, workspaceId, to.name(), runId, errCode, truncate(errMsg, 1024));
        });
    }

    private void markFailed(
            String taskId, String workspaceId, String runId, String code, String message) {
        forceStatus(taskId, workspaceId, TaskStatus.FAILED, runId, code, message);
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static String stringOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static boolean isTerminal(String status) {
        return TaskStatus.isTerminal(status);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 从任务行解析 knowledge_base_ids JSON */
    private List<String> parseKbIds(ResearchTask row) {
        if (row == null || row.getKnowledgeBaseIds() == null || row.getKnowledgeBaseIds().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(row.getKnowledgeBaseIds(), new TypeReference<List<String>>() {});
        } catch (Exception ex) {
            log.warn("parse knowledgeBaseIds failed taskId={}", row.getId());
            return List.of();
        }
    }

    private record PublishedTaskResult(long eventNo, String json) {
    }
}
