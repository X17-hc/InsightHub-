package com.insighthub.task;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insighthub.web.dto.AgentEventDto;

/**
 * 研究任务 / 事件 / 报告的最小 JDBC 持久化。
 */
@Repository
public class TaskRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TaskRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 插入 CREATED 状态任务。
     */
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

    /**
     * 更新任务终态。
     */
    public void updateTaskFinished(
            String taskId,
            String status,
            String runId,
            String errorCode,
            String errorMessage) {
        jdbcTemplate.update(
                """
                UPDATE research_task
                SET status = ?,
                    current_run_id = ?,
                    progress = ?,
                    error_code = ?,
                    error_message = ?,
                    started_at = COALESCE(started_at, NOW()),
                    completed_at = NOW(),
                    updated_at = NOW()
                WHERE id = ?
                """,
                status,
                runId,
                "COMPLETED".equals(status) ? 100 : 0,
                errorCode,
                errorMessage,
                taskId);
    }

    /**
     * 批量写入任务事件。
     */
    public void insertEvents(String taskId, List<AgentEventDto> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        for (AgentEventDto event : events) {
            String payload;
            try {
                Map<String, Object> data = event.getData() == null ? Map.of() : event.getData();
                payload = objectMapper.writeValueAsString(data);
            } catch (JsonProcessingException e) {
                payload = "{}";
            }
            jdbcTemplate.update(
                    """
                    INSERT INTO task_event
                      (task_id, event_no, run_id, node_name, event_type, payload_json, created_at)
                    VALUES (?, ?, ?, ?, ?, CAST(? AS JSON), ?)
                    """,
                    taskId,
                    event.getEventId(),
                    event.getRunId(),
                    event.getNode(),
                    event.getType(),
                    payload,
                    parseTs(event.getTimestamp()));
        }
    }

    /**
     * 写入报告 version=1。
     */
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
}
