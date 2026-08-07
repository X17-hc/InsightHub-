package com.hechang.insighthub.service.impl;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hechang.insighthub.config.TaskProperties;
import com.hechang.insighthub.integration.AgentStreamClient;
import com.hechang.insighthub.mapper.CitationMapper;
import com.hechang.insighthub.mapper.ReportMapper;
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

/**
 * 异步消费 Python NDJSON 流并落库 / 推送实现。
 */
@Service
public class TaskExecutionServiceImpl implements TaskExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionServiceImpl.class);

    private final AgentStreamClient agentStreamClient;
    private final ResearchTaskMapper researchTaskMapper;
    private final TaskEventMapper taskEventMapper;
    private final ReportMapper reportMapper;
    private final CitationMapper citationMapper;
    private final TaskStateMachine stateMachine;
    private final TaskControlRedis taskControlRedis;
    private final WorkspaceConcurrencyService concurrencyService;
    private final TaskSlotTracker slotTracker;
    private final TaskEventSseHub sseHub;
    private final TaskStreamLease streamLease;
    private final ObjectMapper objectMapper;
    private final TaskProperties taskProperties;
    private final TransactionTemplate transactionTemplate;

    public TaskExecutionServiceImpl(
            AgentStreamClient agentStreamClient,
            ResearchTaskMapper researchTaskMapper,
            TaskEventMapper taskEventMapper,
            ReportMapper reportMapper,
            CitationMapper citationMapper,
            TaskStateMachine stateMachine,
            TaskControlRedis taskControlRedis,
            WorkspaceConcurrencyService concurrencyService,
            TaskSlotTracker slotTracker,
            TaskEventSseHub sseHub,
            TaskStreamLease streamLease,
            ObjectMapper objectMapper,
            TaskProperties taskProperties,
            TransactionTemplate transactionTemplate) {
        this.agentStreamClient = agentStreamClient;
        this.researchTaskMapper = researchTaskMapper;
        this.taskEventMapper = taskEventMapper;
        this.reportMapper = reportMapper;
        this.citationMapper = citationMapper;
        this.stateMachine = stateMachine;
        this.taskControlRedis = taskControlRedis;
        this.concurrencyService = concurrencyService;
        this.slotTracker = slotTracker;
        this.sseHub = sseHub;
        this.streamLease = streamLease;
        this.objectMapper = objectMapper;
        this.taskProperties = taskProperties;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    @Async("taskExecutor")
    public void executeStream(
            String taskId,
            String workspaceId,
            String userId,
            String query,
            String traceId,
            boolean resume) {
        String generation = streamLease.acquire(taskId);
        AtomicInteger badLines = new AtomicInteger();
        try {
            int timeout = taskProperties.getDefaultTimeoutSeconds();
            if (resume) {
                agentStreamClient.resumeTask(
                        taskId, null, traceId, timeout,
                        node -> handleLine(taskId, workspaceId, node, badLines, generation));
            } else {
                long nextEventId = taskEventMapper.maxEventNo(taskId) + 1;
                String idem = taskId + "-stream-" + System.currentTimeMillis();
                List<String> kbIds = parseKbIds(
                        researchTaskMapper.findByIdAndWorkspace(taskId, workspaceId));
                agentStreamClient.streamTask(
                        taskId, workspaceId, userId, query, traceId, timeout,
                        nextEventId <= 1 ? null : nextEventId,
                        idem,
                        kbIds,
                        node -> handleLine(taskId, workspaceId, node, badLines, generation));
            }
            if (!streamLease.isCurrent(taskId, generation)) {
                return;
            }
            // 若流结束仍非终态（异常静默），检查 DB
            ResearchTask row = researchTaskMapper.findByIdAndWorkspace(taskId, workspaceId);
            if (row != null && !isTerminal(row.getStatus()) && !"PAUSED".equalsIgnoreCase(row.getStatus())) {
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
            if (row != null && isTerminal(row.getStatus())) {
                slotTracker.releaseOnce(
                        taskId,
                        workspaceId,
                        permitId -> concurrencyService.release(workspaceId, permitId));
                sseHub.completeTask(taskId);
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
                if ("PAUSED".equalsIgnoreCase(text(node, "status"))) {
                    slotTracker.releaseOnce(
                            taskId,
                            workspaceId,
                            permitId -> concurrencyService.release(workspaceId, permitId));
                }
                publishTaskResult(taskId, published.get());
            }
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
        insertEventIgnoreDuplicate(taskId, eventNo, event);
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
            long firstEventNo = taskEventMapper.maxEventNo(taskId) + 1;
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
            dto.setData(data);
            for (int attempt = 0; attempt < 5; attempt++) {
                long eventNo = firstEventNo + attempt;
                dto.setEventId(eventNo);
                if (insertEventIgnoreDuplicate(taskId, eventNo, dto) > 0) {
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
        boolean paused = "PAUSED".equalsIgnoreCase(current) || "PAUSING".equalsIgnoreCase(current);
        switch (type) {
            case "PLAN_CREATED" -> {
                if (!paused && "RUNNING".equalsIgnoreCase(current)) {
                    researchTaskMapper.updateStatusIfCurrent(
                            taskId, workspaceId, TaskStatus.RUNNING.name(),
                            TaskStatus.RUNNING.name(), 20, node);
                }
            }
            case "NODE_STARTED", "NODE_COMPLETED" -> {
                if (!paused && "RUNNING".equalsIgnoreCase(current)) {
                    researchTaskMapper.updateStatusIfCurrent(
                            taskId, workspaceId, TaskStatus.RUNNING.name(),
                            TaskStatus.RUNNING.name(), null, node);
                }
            }
            case "TASK_PAUSED" -> {
                if ("RUNNING".equalsIgnoreCase(current) || "PAUSING".equalsIgnoreCase(current)) {
                    stateMachine.transition(TaskStatus.valueOf(current), TaskStatus.PAUSED);
                    researchTaskMapper.updateStatusIfCurrent(
                            taskId, workspaceId, current, TaskStatus.PAUSED.name(), null, node);
                }
            }
            case "TASK_COMPLETED" -> {
                if ("RUNNING".equalsIgnoreCase(current) || "PAUSING".equalsIgnoreCase(current)) {
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
                if ("CANCELLED".equalsIgnoreCase(code)) {
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

        if ("PAUSED".equalsIgnoreCase(status)) {
            if ("RUNNING".equalsIgnoreCase(currentStatus) || "PAUSING".equalsIgnoreCase(currentStatus)) {
                stateMachine.transition(TaskStatus.valueOf(currentStatus), TaskStatus.PAUSED);
                researchTaskMapper.updateStatus(taskId, workspaceId, TaskStatus.PAUSED.name(), null, null);
            }
            return true;
        }
        if ("COMPLETED".equalsIgnoreCase(status)) {
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
                String reportId = "report-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                insertReport(reportId, taskId, workspaceId, report, extractTitle(report));
                persistCitations(reportId, taskId, node.get("citations"));
                stateMachine.transition(TaskStatus.GENERATING, TaskStatus.COMPLETED);
                researchTaskMapper.updateTaskFinished(
                        taskId, workspaceId, TaskStatus.COMPLETED.name(), runId, null, null);
            }
            return true;
        }
        if ("CANCELLED".equalsIgnoreCase(status)) {
            forceStatus(taskId, workspaceId, TaskStatus.CANCELLED, runId, errCode, errMsg);
            return true;
        }
        if ("CANCELLED".equalsIgnoreCase(currentStatus) || "COMPLETED".equalsIgnoreCase(currentStatus)) {
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
            try {
                TaskStatus from = TaskStatus.valueOf(row.getStatus());
                if (from != to) {
                    if (to == TaskStatus.CANCELLED) {
                        try {
                            stateMachine.transition(from, TaskStatus.CANCELLED);
                        } catch (Exception ignored) {
                            // 强制取消
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
                // 非法历史状态仍允许收敛到终态
            }
            researchTaskMapper.updateTaskFinished(
                    taskId, workspaceId, to.name(), runId, errCode, truncate(errMsg, 1024));
        });
    }

    private void markFailed(
            String taskId, String workspaceId, String runId, String code, String message) {
        forceStatus(taskId, workspaceId, TaskStatus.FAILED, runId, code, message);
    }

    /** 插入单条事件；uk 冲突时忽略（at-least-once 去重） */
    private int insertEventIgnoreDuplicate(String taskId, long eventNo, AgentEventDto event) {
        String payload;
        try {
            Map<String, Object> data = event.getData() == null ? Map.of() : event.getData();
            payload = objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            payload = "{}";
        }
        return taskEventMapper.insertIgnore(
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
        Report reportEntity = new Report();
        reportEntity.setId(reportId);
        reportEntity.setTaskId(taskId);
        reportEntity.setWorkspaceId(workspaceId);
        reportEntity.setVersion(1);
        reportEntity.setTitle(title);
        reportEntity.setMarkdownContent(markdown);
        reportEntity.setStatus("READY");
        reportMapper.insert(reportEntity);
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

    /** 落库 citations（先清后写，幂等） */
    private void persistCitations(String reportId, String taskId, JsonNode citationsNode) {
        citationMapper.deleteByTaskId(taskId);
        if (citationsNode == null || !citationsNode.isArray()) {
            return;
        }
        int i = 0;
        for (JsonNode c : citationsNode) {
            i++;
            Citation citation = new Citation();
            citation.setId("cit-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            citation.setReportId(reportId);
            citation.setTaskId(taskId);
            int no = c.has("citationNo") && !c.get("citationNo").isNull()
                    ? c.get("citationNo").asInt(i)
                    : i;
            citation.setCitationNo(no);
            citation.setSourceTitle(text(c, "sourceTitle"));
            citation.setSourceUri(text(c, "sourceUri"));
            citation.setSourceType(text(c, "sourceType"));
            citation.setDocumentId(text(c, "documentId"));
            citation.setChunkId(text(c, "chunkId"));
            citation.setQuotedText(text(c, "quotedText"));
            citation.setVerified(c.has("verified") && c.get("verified").asBoolean(false) ? 1 : 0);
            citationMapper.insert(citation);
        }
    }

    private record PublishedTaskResult(long eventNo, String json) {
    }
}
