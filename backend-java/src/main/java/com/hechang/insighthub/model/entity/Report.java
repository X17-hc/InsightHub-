package com.hechang.insighthub.model.entity;

import java.time.LocalDateTime;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import lombok.Data;

/**
 * 研究报告实体，对应表 report。
 */
@Data
@Table("report")
public class Report {

    /** 报告主键 */
    @Id(keyType = KeyType.None)
    private String id;

    @Column("task_id")
    private String taskId;

    @Column("workspace_id")
    private String workspaceId;

    /** 同一任务内的报告版本号 */
    private Integer version;

    private String title;

    @Column("markdown_content")
    private String markdownContent;

    /** 状态：DRAFT/READY/ARCHIVED */
    private String status;

    @Column(value = "created_at", onInsertValue = "now()")
    private LocalDateTime createdAt;

    @Column(value = "updated_at", onInsertValue = "now()", onUpdateValue = "now()")
    private LocalDateTime updatedAt;
}
