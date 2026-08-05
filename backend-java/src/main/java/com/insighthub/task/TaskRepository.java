package com.insighthub.task;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insighthub.web.dto.AgentEventDto;

/**
 * 研究任务 / 事件 / 报告 JDBC 持久化（强制 workspace 过滤）。
 */
@Repository
public class TaskRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TaskRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void insertCreatedTask(
            String taskId,
            String workspaceId,
            String creatorId,
            String query,
            String traceId) {
        jdbcTemplate.update(
                """
                INSERT INTO research_task
                  (id, workspace_id, creator_id, query, status, progress, trace_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'CREATED', 0, ?, NOW(), NOW())
                """,
                taskId, workspaceId, creatorId, query, traceId);
    }

    public void updateStatus(String taskId, String workspaceId, String status, Integer progress, String currentNode) {
        jdbcTemplate.update(
                """
                UPDATE research_task
                SET status = ?,
                    progress = COALESCE(?, progress),
                    current_node = COALESCE(?, current_node),
                    started_at = COALESCE(started_at, NOW()),
                    updated_at = NOW()
                WHERE id = ? AND workspace_id = ?
                """,
                status, progress, currentNode, taskId, workspaceId);
    }

    /**
     * 写入终态。COMPLETED 进度固定 100；失败保留已有 progress，不强制清零。
     * error_message 截断至 1024，避免超出列长导致落库失败。
     */
    public void updateTaskFinished(
            String taskId,
            String workspaceId,
            String status,
            String runId,
            String errorCode,
            String errorMessage) {
        String truncated = truncate(errorMessage, 1024);
        jdbcTemplate.update(
                """
                UPDATE research_task
                SET status = ?,
                    current_run_id = ?,
                    progress = CASE WHEN ? = 'COMPLETED' THEN 100 ELSE progress END,
                    error_code = ?,
                    error_message = ?,
                    started_at = COALESCE(started_at, NOW()),
                    completed_at = NOW(),
                    updated_at = NOW()
                WHERE id = ? AND workspace_id = ?
                """,
                status,
                runId,
                status,
                errorCode,
                truncated,
                taskId,
                workspaceId);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public Optional<TaskRow> findByIdAndWorkspace(String taskId, String workspaceId) {
        List<TaskRow> list = jdbcTemplate.query(
                """
                SELECT id, workspace_id, creator_id, query, status, progress, trace_id, current_run_id,
                       error_code, error_message, created_at
                FROM research_task
                WHERE id = ? AND workspace_id = ?
                """,
                (rs, i) -> new TaskRow(
                        rs.getString("id"),
                        rs.getString("workspace_id"),
                        rs.getString("creator_id"),
                        rs.getString("query"),
                        rs.getString("status"),
                        rs.getInt("progress"),
                        rs.getString("trace_id"),
                        rs.getString("current_run_id"),
                        rs.getString("error_code"),
                        rs.getString("error_message"),
                        rs.getTimestamp("created_at")),
                taskId, workspaceId);
        return list.stream().findFirst();
    }

    public List<TaskRow> listByWorkspace(String workspaceId) {
        return jdbcTemplate.query(
                """
                SELECT id, workspace_id, creator_id, query, status, progress, trace_id, current_run_id,
                       error_code, error_message, created_at
                FROM research_task
                WHERE workspace_id = ?
                ORDER BY created_at DESC
                """,
                (rs, i) -> new TaskRow(
                        rs.getString("id"),
                        rs.getString("workspace_id"),
                        rs.getString("creator_id"),
                        rs.getString("query"),
                        rs.getString("status"),
                        rs.getInt("progress"),
                        rs.getString("trace_id"),
                        rs.getString("current_run_id"),
                        rs.getString("error_code"),
                        rs.getString("error_message"),
                        rs.getTimestamp("created_at")),
                workspaceId);
    }

    public void insertEvents(String taskId, List<AgentEventDto> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        for (int i = 0; i < events.size(); i++) {
            AgentEventDto event = events.get(i);
            long eventNo = event.getEventId() != null ? event.getEventId() : (i + 1L);
            insertEventIgnoreDuplicate(taskId, eventNo, event);
        }
    }

    /**
     * 插入单条事件；uk 冲突时忽略（at-least-once 去重）。
     *
     * @return true 表示新插入
     */
    public boolean insertEventIgnoreDuplicate(String taskId, long eventNo, AgentEventDto event) {
        String payload;
        try {
            Map<String, Object> data = event.getData() == null ? Map.of() : event.getData();
            payload = objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            payload = "{}";
        }
        int n = jdbcTemplate.update(
                """
                INSERT IGNORE INTO task_event
                  (task_id, event_no, run_id, node_name, event_type, payload_json, created_at)
                VALUES (?, ?, ?, ?, ?, CAST(? AS JSON), ?)
                """,
                taskId,
                eventNo,
                event.getRunId(),
                event.getNode(),
                event.getType(),
                payload,
                parseTs(event.getTimestamp()));
        return n > 0;
    }

    /** 当前任务已分配的最大 event_no；无事件时返回 0。 */
    public long maxEventNo(String taskId) {
        Long v = jdbcTemplate.query(
                "SELECT COALESCE(MAX(event_no), 0) AS m FROM task_event WHERE task_id = ?",
                rs -> rs.next() ? rs.getLong("m") : 0L,
                taskId);
        return v == null ? 0L : v;
    }

    /**
     * SSE 续传：查询 event_no 大于 fromEventNo 的事件。
     */
    public List<EventRow> listEventsAfter(String taskId, long fromEventNo) {
        return jdbcTemplate.query(
                """
                SELECT event_no, run_id, node_name, event_type, payload_json, created_at
                FROM task_event
                WHERE task_id = ? AND event_no > ?
                ORDER BY event_no ASC
                """,
                (rs, i) -> new EventRow(
                        rs.getLong("event_no"),
                        rs.getString("run_id"),
                        rs.getString("node_name"),
                        rs.getString("event_type"),
                        rs.getString("payload_json"),
                        rs.getTimestamp("created_at")),
                taskId,
                fromEventNo);
    }

    /** 重试前清除错误并写入新 runId。 */
    public void prepareRetry(String taskId, String workspaceId, String runId) {
        jdbcTemplate.update(
                """
                UPDATE research_task
                SET current_run_id = ?,
                    error_code = NULL,
                    error_message = NULL,
                    completed_at = NULL,
                    updated_at = NOW()
                WHERE id = ? AND workspace_id = ?
                """,
                runId, taskId, workspaceId);
    }

    public void insertReport(String reportId, String taskId, String workspaceId, String markdown, String title) {
        jdbcTemplate.update(
                """
                INSERT INTO report
                  (id, task_id, workspace_id, version, title, markdown_content, status, created_at, updated_at)
                VALUES (?, ?, ?, 1, ?, ?, 'READY', NOW(), NOW())
                """,
                reportId, taskId, workspaceId, title, markdown);
    }

    private static Timestamp parseTs(String iso) {
        if (iso == null || iso.isBlank()) {
            return Timestamp.from(Instant.now());
        }
        try {
            return Timestamp.from(Instant.parse(iso));
        } catch (Exception ex) {
            return Timestamp.from(Instant.now());
        }
    }

    public record TaskRow(
            String id,
            String workspaceId,
            String creatorId,
            String query,
            String status,
            int progress,
            String traceId,
            String currentRunId,
            String errorCode,
            String errorMessage,
            Timestamp createdAt) {
    }

    public record EventRow(
            long eventNo,
            String runId,
            String nodeName,
            String eventType,
            String payloadJson,
            Timestamp createdAt) {
    }
}
