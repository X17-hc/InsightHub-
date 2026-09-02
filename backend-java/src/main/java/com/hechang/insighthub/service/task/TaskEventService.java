package com.hechang.insighthub.service.task;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hechang.insighthub.mapper.TaskEventMapper;
import com.hechang.insighthub.model.dto.task.AgentEventDto;
import com.hechang.insighthub.model.dto.task.TaskEventResponse;
import com.hechang.insighthub.model.entity.TaskEvent;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;

/** 任务事件适配器：负责协议解析、编号去重和事件持久化。 */
@Service
@RequiredArgsConstructor
public class TaskEventService {

    private final TaskEventMapper mapper;
    private final ObjectMapper objectMapper;

    public AgentEventDto toDto(JsonNode node) {
        AgentEventDto dto = new AgentEventDto();
        dto.setSchemaVersion(text(node, "schemaVersion") == null ? "1.0" : text(node, "schemaVersion"));
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
            node.get("data").fields().forEachRemaining(entry -> data.put(entry.getKey(), unwrap(entry.getValue())));
            dto.setData(data);
        }
        return dto;
    }

    /** 事件表按 (taskId,eventNo) 唯一约束实现 at-least-once 去重。 */
    public int insertIgnoreDuplicate(String taskId, long eventNo, AgentEventDto event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event.getData() == null ? Map.of() : event.getData());
        } catch (JsonProcessingException ex) {
            payload = "{}";
        }
        return mapper.insertIgnore(
                taskId,
                eventNo,
                event.getRunId(),
                event.getNode(),
                event.getType(),
                payload,
                parseTimestamp(event.getTimestamp()));
    }

    public void insertAll(String taskId, Iterable<AgentEventDto> events) {
        long fallbackNo = 1;
        for (AgentEventDto event : events) {
            long eventNo = event.getEventId() == null ? fallbackNo : event.getEventId();
            insertIgnoreDuplicate(taskId, eventNo, event);
            fallbackNo = eventNo + 1;
        }
    }

    /** 将事件表记录转换为 REST 时间线 DTO。 */
    public TaskEventResponse toResponse(String taskId, TaskEvent row) {
        Map<String, Object> data = readPayload(row.getPayloadJson());
        String timestamp = row.getCreatedAt() == null
                ? null
                : row.getCreatedAt().toInstant(ZoneOffset.UTC).toString();
        return new TaskEventResponse(
                row.getEventNo() == null ? 0L : row.getEventNo(),
                taskId,
                row.getRunId(),
                row.getNodeName(),
                row.getEventType(),
                timestamp,
                data);
    }

    /** 生成用于 SSE/Redis 发布的统一事件信封。 */
    public String toClientJson(String taskId, TaskEvent row) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            TaskEventResponse event = toResponse(taskId, row);
            body.put("schemaVersion", "1.0");
            body.put("eventId", event.getEventId());
            body.put("taskId", event.getTaskId());
            body.put("runId", event.getRunId());
            body.put("node", event.getNode());
            body.put("type", event.getType());
            body.put("timestamp", event.getTimestamp());
            body.put("data", event.getData());
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            return "{\"eventId\":" + row.getEventNo() + ",\"type\":\"" + row.getEventType() + "\"}";
        }
    }

    /** 在终态事务中写入可回放的 TASK_RESULT，并返回待发布的事件信封。 */
    public StoredEvent insertTerminalResult(
            String taskId, String runId, String status, Map<String, Object> error) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status);
        data.put("error", error);
        data.put("hasReport", false);

        String payload = writeJson(data, "serialize terminal event payload failed");
        long firstEventNo = maxEventNo(taskId) + 1;
        String timestamp = Instant.now().toString();
        for (int attempt = 0; attempt < 5; attempt++) {
            long eventNo = firstEventNo + attempt;
            if (mapper.insertIgnore(taskId, eventNo, runId, null, "TASK_RESULT", payload, parseTimestamp(timestamp)) > 0) {
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("schemaVersion", "1.0");
                envelope.put("eventId", eventNo);
                envelope.put("taskId", taskId);
                envelope.put("runId", runId);
                envelope.put("node", null);
                envelope.put("type", "TASK_RESULT");
                envelope.put("timestamp", timestamp);
                envelope.put("data", data);
                return new StoredEvent(eventNo, writeJson(envelope, "serialize terminal event envelope failed"));
            }
        }
        throw new IllegalStateException("unable to allocate terminal event number");
    }

    /** Stores a sanitized Agent TASK_RESULT; report content never enters the event log or SSE payload. */
    public StoredEvent insertAgentResult(String taskId, JsonNode result) {
        String runId = text(result, "runId");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", text(result, "status"));
        if (result.has("error") && !result.get("error").isNull()) {
            data.put("error", objectMapper.convertValue(result.get("error"), Map.class));
        }
        data.put("hasReport", result.has("reportMarkdown")
                && !result.get("reportMarkdown").isNull()
                && !result.get("reportMarkdown").asText("").isBlank());
        if (result.has("quality") && result.get("quality").isObject()) {
            data.put("quality", objectMapper.convertValue(result.get("quality"), Map.class));
        }
        return insertServerEvent(taskId, runId, null, "TASK_RESULT", data);
    }

    /** Persist a Java-originated task event using the same task-scoped event sequence as Agent events. */
    public StoredEvent insertServerEvent(
            String taskId, String runId, String node, String type, Map<String, Object> data) {
        Map<String, Object> safeData = data == null ? Map.of() : Map.copyOf(data);
        String payload = writeJson(safeData, "serialize task event payload failed");
        String timestamp = Instant.now().toString();
        long firstEventNo = maxEventNo(taskId) + 1;
        for (int attempt = 0; attempt < 5; attempt++) {
            long eventNo = firstEventNo + attempt;
            if (mapper.insertIgnore(taskId, eventNo, runId, node, type, payload, parseTimestamp(timestamp)) > 0) {
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("schemaVersion", "1.0");
                envelope.put("eventId", eventNo);
                envelope.put("taskId", taskId);
                envelope.put("runId", runId);
                envelope.put("node", node);
                envelope.put("type", type);
                envelope.put("timestamp", timestamp);
                envelope.put("data", safeData);
                return new StoredEvent(eventNo, writeJson(envelope, "serialize task event envelope failed"));
            }
        }
        throw new IllegalStateException("unable to allocate task event number");
    }

    private static LocalDateTime parseTimestamp(String value) {
        if (value == null || value.isBlank()) return LocalDateTime.now(ZoneOffset.UTC);
        try {
            return LocalDateTime.ofInstant(Instant.parse(value), ZoneOffset.UTC);
        } catch (RuntimeException ignored) {
            return LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    public long maxEventNo(String taskId) {
        Object value = mapper.selectObjectByQuery(QueryWrapper.create()
                .select("COALESCE(MAX(event_no), 0)")
                .eq(TaskEvent::getTaskId, taskId));
        return value instanceof Number number ? number.longValue() : 0L;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPayload(String payload) {
        if (payload == null || payload.isBlank()) return Map.of();
        try {
            Map<String, Object> data = objectMapper.readValue(payload, Map.class);
            return data == null ? Map.of() : data;
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private String writeJson(Object value, String errorMessage) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(errorMessage, ex);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Object unwrap(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isNumber()) return node.numberValue();
        if (node.isBoolean()) return node.asBoolean();
        return objectMapper.convertValue(node, Object.class);
    }

    public record StoredEvent(long eventNo, String json) {
    }
}
