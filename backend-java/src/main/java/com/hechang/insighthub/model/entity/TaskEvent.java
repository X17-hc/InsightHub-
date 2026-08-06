package com.hechang.insighthub.model.entity;

import java.time.LocalDateTime;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import lombok.Data;

/**
 * 任务事件实体，对应表 task_event。
 */
@Data
@Table("task_event")
public class TaskEvent {

    /** 自增主键 */
    @Id(keyType = KeyType.Auto)
    private Long id;

    @Column("task_id")
    private String taskId;

    /** 任务内事件序号（单调递增） */
    @Column("event_no")
    private Long eventNo;

    @Column("run_id")
    private String runId;

    @Column("node_run_id")
    private String nodeRunId;

    @Column("node_name")
    private String nodeName;

    @Column("event_type")
    private String eventType;

    /** 事件载荷 JSON 字符串 */
    @Column("payload_json")
    private String payloadJson;

    @Column(value = "created_at", onInsertValue = "now()")
    private LocalDateTime createdAt;
}
