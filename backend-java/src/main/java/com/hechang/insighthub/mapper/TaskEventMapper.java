package com.hechang.insighthub.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.hechang.insighthub.model.entity.TaskEvent;
import com.mybatisflex.core.BaseMapper;

/**
 * 任务事件 Mapper。
 */
@Mapper
public interface TaskEventMapper extends BaseMapper<TaskEvent> {

    /**
     * 插入事件；uk(task_id, event_no) 冲突时忽略（at-least-once 去重）。
     *
     * @return 影响行数（冲突时为 0）
     */
    @Insert("""
            INSERT IGNORE INTO task_event
              (task_id, event_no, run_id, node_name, event_type, payload_json, created_at)
            VALUES (#{taskId}, #{eventNo}, #{runId}, #{nodeName}, #{eventType},
                    CAST(#{payloadJson} AS JSON), #{createdAt})
            """)
    int insertIgnore(
            @Param("taskId") String taskId,
            @Param("eventNo") long eventNo,
            @Param("runId") String runId,
            @Param("nodeName") String nodeName,
            @Param("eventType") String eventType,
            @Param("payloadJson") String payloadJson,
            @Param("createdAt") LocalDateTime createdAt);

    /** 当前任务已分配的最大 event_no；无事件时返回 0 */
    @Select("SELECT COALESCE(MAX(event_no), 0) FROM task_event WHERE task_id = #{taskId}")
    long maxEventNo(@Param("taskId") String taskId);

    /**
     * SSE 续传：查询 event_no 大于 fromEventNo 的事件。
     *
     * @param taskId      任务 ID
     * @param fromEventNo 起始序号（不包含）
     * @return 事件列表
     */
    @Select("""
            SELECT id AS id,
                   task_id AS taskId,
                   event_no AS eventNo,
                   run_id AS runId,
                   node_run_id AS nodeRunId,
                   node_name AS nodeName,
                   event_type AS eventType,
                   payload_json AS payloadJson,
                   created_at AS createdAt
            FROM task_event
            WHERE task_id = #{taskId} AND event_no > #{fromEventNo}
            ORDER BY event_no ASC
            """)
    List<TaskEvent> listAfterEventNo(@Param("taskId") String taskId, @Param("fromEventNo") long fromEventNo);
}
