package com.hechang.insighthub.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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

}
