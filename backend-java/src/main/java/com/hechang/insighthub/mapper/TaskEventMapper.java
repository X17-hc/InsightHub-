package com.hechang.insighthub.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import com.hechang.insighthub.model.entity.TaskEvent;
import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;

/**
 * 任务事件 Mapper。
 */
public interface TaskEventMapper extends BaseMapper<TaskEvent> {

    default List<TaskEvent> listAfterEventNo(String taskId, long fromEventNo) {
        return selectListByQuery(QueryWrapper.create()
                .eq(TaskEvent::getTaskId, taskId)
                .gt(TaskEvent::getEventNo, Math.max(0L, fromEventNo))
                .orderBy(TaskEvent::getEventNo, true));
    }

    default int deleteByTaskId(String taskId) {
        return deleteByQuery(QueryWrapper.create().eq(TaskEvent::getTaskId, taskId));
    }

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
