package com.insighthub.task;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.insighthub.config.TaskProperties;
import com.insighthub.integration.AgentStreamClient;
import com.insighthub.redis.TaskControlRedis;
import com.insighthub.redis.TaskSlotTracker;
import com.insighthub.redis.WorkspaceConcurrencyService;
import com.insighthub.web.dto.AgentEventDto;

/**
 * 异步消费 Python NDJSON 流并落库 / 推送。
 */
@Service
public class TaskExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionService.class);

    private final AgentStreamClient agentStreamClient;
    private final TaskRepository taskRepository;
    private final TaskStateMachine stateMachine;
    private final TaskControlRedis taskControlRedis;
    private final WorkspaceConcurrencyService concurrencyService;
    private final TaskSlotTracker slotTracker;
    private final TaskEventSseHub sseHub;
    private final TaskStreamLease streamLease;
    private final ObjectMapper objectMapper;
    private final TaskProperties taskProperties;

    public TaskExecutionService(
            AgentStreamClient agentStreamClient,
            TaskRepository taskRepository,
            TaskStateMachine stateMachine,
            TaskControlRedis taskControlRedis,
            WorkspaceConcurrencyService concurrencyService,
            TaskSlotTracker slotTracker,
            TaskEventSseHub sseHub,
            TaskStreamLease streamLease,
            ObjectMapper objectMapper,
            TaskProperties taskProperties) {
        this.agentStreamClient = agentStreamClient;
        this.taskRepository = taskRepository;
        this.stateMachine = stateMachine;
        this.taskControlRedis = taskControlRedis;
        this.concurrencyService = concurrencyService;
        this.slotTracker = slotTracker;
        this.sseHub = sseHub;
        this.streamLease = streamLease;
        this.objectMapper = objectMapper;
        this.taskProperties = taskProperties;
    }

    @Async("taskExecutor")
    public void executeStream(
            String taskId,
            String workspaceId,
            String userId,
            String query,
            String traceId,
            boolean resume) {
        long generation = streamLease.acquire(taskId);
        AtomicInteger badLines = new AtomicInteger();
        try {
            int timeout = taskProperties.getDefaultTimeoutSeconds();
            if (resume) {
                agentStreamClient.resumeTask(
                        taskId, null, traceId, timeout,
                        node -> handleLine(taskId, workspaceId, node, badLines, generation));
            } else {
                long nextEventId = taskRepository.maxEventNo(taskId) + 1;
                String idem = taskId + "-stream-" + System.currentTimeMillis();
                agentStreamClient.streamTask(
                        taskId, workspaceId, userId, query, traceId, timeout,
                        nextEventId <= 1 ? null : nextEventId,
                        idem,
                        node -> handleLine(taskId, workspaceId, node, badLines, generation));
            }
            if (!streamLease.isCurrent(taskId, generation)) {
                return;
            }
            // 若流结束仍非终态（异常静默），检查 DB
            taskRepository.findByIdAndWorkspace(taskId, workspaceId).ifPresent(row -> {
                if (!isTerminal(row.status()) && !"PAUSED".equalsIgnoreCase(row.status())) {
                    markFailed(taskId, workspaceId, null, "AGENT_STREAM_INCOMPLETE", "stream ended without result");
                }
            });
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
            taskRepository.findByIdAndWorkspace(taskId, workspaceId).ifPresent(row -> {
                if (isTerminal(row.status())) {
                    slotTracker.releaseOnce(taskId, workspaceId, () -> concurrencyService.release(workspaceId));
                    sseHub.completeTask(taskId);
                }
            });
        }
    }

    private void handleLine(
            String taskId,
            String workspaceId,
            JsonNode node,
            AtomicInteger badLines,
            long generation) {
        if (!streamLease.isCurrent(taskId, generation)) {
            return;
        }
        if (node == null || node.isNull()) {
            return;
        }
        String type = text(node, "type");
        if ("TASK_RESULT".equals(type)) {
            finalizeResult(taskId, workspaceId, node);
            publishTaskResult(taskId, node);
            return;
        }
        AgentEventDto event = toEventDto(node);
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
        taskRepository.insertEventIgnoreDuplicate(taskId, eventNo, event);
        try {
            String json = objectMapper.writeValueAsString(node);
            taskControlRedis.publishEvent(taskId, json);
            sseHub.broadcastLocal(taskId, eventNo, event.getType(), json);
        } catch (Exception ex) {
            log.warn("publish event failed taskId={} eventNo={}", taskId, eventNo, ex);
        }
        applySideEffects(taskId, workspaceId, event);
    }

    /**
     * 将 TASK_RESULT 分配 eventNo 后落库并推送 SSE（含 PAUSED）。
     */
    private void publishTaskResult(String taskId, JsonNode node) {
        try {
            long eventNo = taskRepository.maxEventNo(taskId) + 1;
            String status = text(node, "status");
            String runId = text(node, "runId");
            AgentEventDto dto = new AgentEventDto();
            dto.setEventId(eventNo);
            dto.setTaskId(taskId);
            dto.setRunId(runId);
            dto.setType("TASK_RESULT");
            dto.setTimestamp(java.time.Instant.now().toString());
            Map<String, Object> data = new HashMap<>();
            data.put("status", status);
            if (node.has("error") && !node.get("error").isNull()) {
                data.put("error", objectMapper.convertValue(node.get("error"), Map.class));
            }
            // 报告可能很大，SSE/事件表只记是否有报告
            data.put("hasReport", node.has("reportMarkdown")
                    && !node.get("reportMarkdown").isNull()
                    && !node.get("reportMarkdown").asText("").isBlank());
            dto.setData(data);
            taskRepository.insertEventIgnoreDuplicate(taskId, eventNo, dto);

            ObjectNode out = node.deepCopy();
            out.put("eventId", eventNo);
            String json = objectMapper.writeValueAsString(out);
            taskControlRedis.publishEvent(taskId, json);
            sseHub.broadcastLocal(taskId, eventNo, "TASK_RESULT", json);
        } catch (Exception ex) {
            log.warn("publish TASK_RESULT failed taskId={}", taskId, ex);
        }
    }

    private void applySideEffects(String taskId, String workspaceId, AgentEventDto event) {
        String type = event.getType();
        String node = event.getNode();
        String current = taskRepository.findByIdAndWorkspace(taskId, workspaceId)
                .map(TaskRepository.TaskRow::status)
                .orElse(null);
        if (current == null || isTerminal(current)) {
            return;
        }
        boolean paused = "PAUSED".equalsIgnoreCase(current);
        switch (type) {
            case "PLAN_CREATED" -> {
                if (!paused && "RUNNING".equalsIgnoreCase(current)) {
                    taskRepository.updateStatus(taskId, workspaceId, TaskStatus.RUNNING.name(), 20, node);
                }
            }
            case "NODE_STARTED", "NODE_COMPLETED" -> {
                if (!paused && "RUNNING".equalsIgnoreCase(current)) {
                    taskRepository.updateStatus(taskId, workspaceId, TaskStatus.RUNNING.name(), null, node);
                }
            }
            case "TASK_PAUSED" -> {
                if ("RUNNING".equalsIgnoreCase(current)) {
                    stateMachine.transition(TaskStatus.RUNNING, TaskStatus.PAUSED);
                    taskRepository.updateStatus(taskId, workspaceId, TaskStatus.PAUSED.name(), null, node);
                }
            }
            case "TASK_COMPLETED" -> {
                if ("RUNNING".equalsIgnoreCase(current)) {
                    stateMachine.transition(TaskStatus.RUNNING, TaskStatus.GENERATING);
                    taskRepository.updateStatus(taskId, workspaceId, TaskStatus.GENERATING.name(), 80, "write_report");
                }
            }
            case "TASK_FAILED" -> {
                String code = null;
                String msg = null;
                if (event.getData() != null) {
                    code = stringOrNull(event.getData().get("code"));
                    msg = stringOrNull(event.getData().get("message"));
                }
                if ("CANCELLED".equalsIgnoreCase(code)) {
                    forceStatus(taskId, workspaceId, TaskStatus.CANCELLED, event.getRunId(), code, msg);
                }
            }
            default -> {
                // no-op
            }
        }
    }

    private void finalizeResult(String taskId, String workspaceId, JsonNode node) {
        String status = text(node, "status");
        String runId = text(node, "runId");
        String report = text(node, "reportMarkdown");
        JsonNode err = node.get("error");
        String errCode = err != null && !err.isNull() ? text(err, "code") : null;
        String errMsg = err != null && !err.isNull() ? text(err, "message") : null;

        var rowOpt = taskRepository.findByIdAndWorkspace(taskId, workspaceId);
        if (rowOpt.isEmpty()) {
            return;
        }
        String currentStatus = rowOpt.get().status();
        if (isTerminal(currentStatus)
                && !"CANCELLED".equalsIgnoreCase(status)
                && !currentStatus.equalsIgnoreCase(status)) {
            log.info("ignore late TASK_RESULT status={} current={} taskId={}", status, currentStatus, taskId);
            return;
        }

        if ("PAUSED".equalsIgnoreCase(status)) {
            if ("RUNNING".equalsIgnoreCase(currentStatus)) {
                stateMachine.transition(TaskStatus.RUNNING, TaskStatus.PAUSED);
                taskRepository.updateStatus(taskId, workspaceId, TaskStatus.PAUSED.name(), null, null);
            }
            return;
        }
        if ("COMPLETED".equalsIgnoreCase(status)) {
            if (isTerminal(currentStatus)) {
                return;
            }
            TaskStatus cur = TaskStatus.valueOf(currentStatus);
            if (cur == TaskStatus.PAUSED) {
                log.info("ignore COMPLETED while PAUSED taskId={}", taskId);
                return;
            }
            if (cur == TaskStatus.RUNNING) {
                stateMachine.transition(TaskStatus.RUNNING, TaskStatus.GENERATING);
                taskRepository.updateStatus(taskId, workspaceId, TaskStatus.GENERATING.name(), 80, "write_report");
                cur = TaskStatus.GENERATING;
            }
            if (cur == TaskStatus.GENERATING) {
                stateMachine.transition(TaskStatus.GENERATING, TaskStatus.COMPLETED);
                taskRepository.updateTaskFinished(
                        taskId, workspaceId, TaskStatus.COMPLETED.name(), runId, null, null);
                if (report != null && !report.isBlank()) {
                    try {
                        String reportId = "report-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                        taskRepository.insertReport(reportId, taskId, workspaceId, report, extractTitle(report));
                    } catch (Exception ex) {
                        log.error("report persist failed taskId={}", taskId, ex);
                        taskRepository.updateTaskFinished(
                                taskId, workspaceId, TaskStatus.COMPLETED.name(), runId,
                                "REPORT_PERSIST_FAILED", ex.getMessage());
                    }
                }
            }
            return;
        }
        if ("CANCELLED".equalsIgnoreCase(status)) {
            forceStatus(taskId, workspaceId, TaskStatus.CANCELLED, runId, errCode, errMsg);
            return;
        }
        if ("CANCELLED".equalsIgnoreCase(currentStatus) || "COMPLETED".equalsIgnoreCase(currentStatus)) {
            return;
        }
        forceStatus(taskId, workspaceId, TaskStatus.FAILED, runId, errCode, errMsg);
    }

    private void forceStatus(
            String taskId,
            String workspaceId,
            TaskStatus to,
            String runId,
            String errCode,
            String errMsg) {
        var row = taskRepository.findByIdAndWorkspace(taskId, workspaceId).orElse(null);
        if (row == null) {
            return;
        }
        if (isTerminal(row.status()) && to != TaskStatus.CANCELLED) {
            return;
        }
        try {
            TaskStatus from = TaskStatus.valueOf(row.status());
            if (from != to) {
                if (to == TaskStatus.CANCELLED) {
                    if (from == TaskStatus.COMPLETED) {
                        return;
                    }
                    if (from != TaskStatus.CANCELLED) {
                        try {
                            stateMachine.transition(from, TaskStatus.CANCELLED);
                        } catch (Exception ignored) {
                            // 强制取消
                        }
                    }
                } else if (to == TaskStatus.FAILED) {
                    try {
                        stateMachine.transition(from, TaskStatus.FAILED);
                    } catch (Exception ignored) {
                        // 强制失败
                    }
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        taskRepository.updateTaskFinished(taskId, workspaceId, to.name(), runId, errCode, errMsg);
    }

    private void markFailed(
            String taskId, String workspaceId, String runId, String code, String message) {
        forceStatus(taskId, workspaceId, TaskStatus.FAILED, runId, code, message);
    }

    private static AgentEventDto toEventDto(JsonNode node) {
        AgentEventDto dto = new AgentEventDto();
        if (node.has("eventId") && node.get("eventId").canConvertToLong()) {
            dto.setEventId(node.get("eventId").asLong());
        }
        dto.setTaskId(text(node, "taskId"));
        dto.setRunId(text(node, "runId"));
        dto.setNode(text(node, "node"));
        dto.setType(text(node, "type"));
        dto.setTimestamp(text(node, "timestamp"));
        if (node.has("data") && node.get("data").isObject()) {
            Map<String, Object> data = new HashMap<>();
            node.get("data").fields().forEachRemaining(e -> data.put(e.getKey(), unwrap(e.getValue())));
            dto.setData(data);
        }
        return dto;
    }

    private static Object unwrap(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isTextual()) {
            return n.asText();
        }
        if (n.isNumber()) {
            return n.numberValue();
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        return n.toString();
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static String stringOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static boolean isTerminal(String status) {
        return "COMPLETED".equalsIgnoreCase(status)
                || "FAILED".equalsIgnoreCase(status)
                || "CANCELLED".equalsIgnoreCase(status);
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
