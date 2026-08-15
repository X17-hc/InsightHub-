package com.hechang.insighthub.model.entity;

import java.time.LocalDateTime;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;

@Data
@Table("task_plan_revision")
public class TaskPlanRevision {
    @Id(keyType = KeyType.None)
    private String id;

    @Column("task_id")
    private String taskId;

    @Column("workspace_id")
    private String workspaceId;

    @Column("revision_no")
    private Integer revisionNo;

    private String status;

    @Column("plan_json")
    private String planJson;

    @Column("plan_hash")
    private String planHash;

    @Column("revision_instruction")
    private String revisionInstruction;

    @Column("created_by")
    private String createdBy;

    @Column("approved_by")
    private String approvedBy;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("approved_at")
    private LocalDateTime approvedAt;
}