package com.hechang.insighthub.model.entity;

import java.time.LocalDateTime;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import lombok.Data;

/**
 * 研究任务实体，对应表 research_task。
 */
@Data
@Table("research_task")
public class ResearchTask {

    /** 任务主键 */
    @Id(keyType = KeyType.None)
    private String id;

    @Column("workspace_id")
    private String workspaceId;

    @Column("creator_id")
    private String creatorId;

    @Column("workflow_id")
    private String workflowId;

    private String title;

    /** 用户原始研究问题 */
    private String query;

    @Column("clarified_query")
    private String clarifiedQuery;

    @Column("plan_json")
    private String planJson;

    /** 计划是否已确认：1=是 0=否 */
    @Column("plan_approved")
    private Integer planApproved;

    @Column("current_plan_revision_id")
    private String currentPlanRevisionId;

    private String status;

    @Column("current_node")
    private String currentNode;

    /** 进度百分比 0-100 */
    private Integer progress;

    @Column("config_json")
    private String configJson;

    @Column("knowledge_base_ids")
    private String knowledgeBaseIds;

    @Column("trace_id")
    private String traceId;

    @Column("current_run_id")
    private String currentRunId;

    @Column("error_code")
    private String errorCode;

    @Column("error_message")
    private String errorMessage;

    @Column("started_at")
    private LocalDateTime startedAt;

    @Column("completed_at")
    private LocalDateTime completedAt;

    @Column(value = "created_at", onInsertValue = "now()")
    private LocalDateTime createdAt;

    @Column(value = "updated_at", onInsertValue = "now()", onUpdateValue = "now()")
    private LocalDateTime updatedAt;
}
