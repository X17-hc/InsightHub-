package com.hechang.insighthub.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.hechang.insighthub.model.entity.ResearchTask;
import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;

/**
 * 研究任务 Mapper。
 */
public interface ResearchTaskMapper extends BaseMapper<ResearchTask> {

    /** 按 ID + 工作空间查询（强制租户隔离）。 */
    default ResearchTask findByIdAndWorkspace(String id, String workspaceId) {
        return selectOneByQuery(QueryWrapper.create()
                .eq(ResearchTask::getId, id)
                .eq(ResearchTask::getWorkspaceId, workspaceId));
    }

    /** 在终态事务内锁定任务行，避免暂停、取消与完成相互覆盖。 */
    @Select("""
            SELECT id AS id,
                   workspace_id AS workspaceId,
                   creator_id AS creatorId,
                   query AS query,
                   status AS status,
                   current_plan_revision_id AS currentPlanRevisionId,
                   plan_approved AS planApproved,
                   knowledge_base_ids AS knowledgeBaseIds,
                   quality_status AS qualityStatus,
                   trace_id AS traceId,
                   current_run_id AS currentRunId
            FROM research_task
            WHERE id = #{id} AND workspace_id = #{workspaceId}
            FOR UPDATE
            """)
    ResearchTask findByIdAndWorkspaceForUpdate(
            @Param("id") String id,
            @Param("workspaceId") String workspaceId);

    /** 按工作空间列出任务（创建时间倒序）。 */
    default List<ResearchTask> listByWorkspace(String workspaceId) {
        return selectListByQuery(QueryWrapper.create()
                .eq(ResearchTask::getWorkspaceId, workspaceId)
                .orderBy(ResearchTask::getCreatedAt, false));
    }

    /**
     * 更新任务状态；progress / current_node 为 null 时用 COALESCE 保留原值。
     */
    @Update("""
            UPDATE research_task
            SET status = #{status},
                progress = COALESCE(#{progress}, progress),
                current_node = COALESCE(#{currentNode}, current_node),
                started_at = COALESCE(started_at, NOW()),
                updated_at = NOW()
            WHERE id = #{id} AND workspace_id = #{workspaceId}
            """)
    int updateStatus(
            @Param("id") String id,
            @Param("workspaceId") String workspaceId,
            @Param("status") String status,
            @Param("progress") Integer progress,
            @Param("currentNode") String currentNode);

    /** 仅当前状态匹配时迁移，防止并发控制请求覆盖终态。 */
    @Update("""
            UPDATE research_task
            SET status = #{toStatus},
                progress = COALESCE(#{progress}, progress),
                current_node = COALESCE(#{currentNode}, current_node),
                started_at = COALESCE(started_at, NOW()),
                updated_at = NOW()
            WHERE id = #{id}
              AND workspace_id = #{workspaceId}
              AND status = #{fromStatus}
            """)
    int updateStatusIfCurrent(
            @Param("id") String id,
            @Param("workspaceId") String workspaceId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("progress") Integer progress,
            @Param("currentNode") String currentNode);

    /**
     * 写入终态：COMPLETED 时 progress=100，否则保留原 progress。
     *
     * @param id           任务 ID
     * @param workspaceId  工作空间 ID
     * @param status       终态状态
     * @param runId        当前执行轮次
     * @param errorCode    错误码（可为 null）
     * @param errorMessage 错误信息（可为 null）
     * @return 影响行数
     */
    @Update("""
            UPDATE research_task
            SET status = #{status},
                current_run_id = #{runId},
                progress = CASE WHEN #{status} = 'COMPLETED' THEN 100 ELSE progress END,
                error_code = #{errorCode},
                error_message = #{errorMessage},
                started_at = COALESCE(started_at, NOW()),
                completed_at = NOW(),
                updated_at = NOW()
            WHERE id = #{id} AND workspace_id = #{workspaceId}
            """)
    int updateTaskFinished(
            @Param("id") String id,
            @Param("workspaceId") String workspaceId,
            @Param("status") String status,
            @Param("runId") String runId,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage);

    /** 重试前清除错误并写入新 runId */
    @Update("""
            UPDATE research_task
            SET current_run_id = #{runId},
                error_code = NULL,
                error_message = NULL,
                quality_status = 'PENDING',
                quality_summary = NULL,
                verified_citation_count = 0,
                total_citation_count = 0,
                completed_at = NULL,
                updated_at = NOW()
            WHERE id = #{id} AND workspace_id = #{workspaceId}
            """)
    int prepareRetry(
            @Param("id") String id,
            @Param("workspaceId") String workspaceId,
            @Param("runId") String runId);

    @Update("""
            UPDATE research_task
            SET quality_status=#{qualityStatus}, quality_summary=#{qualitySummary},
                verified_citation_count=#{verifiedCount}, total_citation_count=#{totalCount}, updated_at=NOW()
            WHERE id=#{id} AND workspace_id=#{workspaceId}
            """)
    int updateQuality(@Param("id") String id, @Param("workspaceId") String workspaceId,
            @Param("qualityStatus") String qualityStatus, @Param("qualitySummary") String qualitySummary,
            @Param("verifiedCount") int verifiedCount, @Param("totalCount") int totalCount);

    /** Persist the current plan projection and task status atomically. */
    @Update("""
            UPDATE research_task
            SET current_plan_revision_id = #{revisionId},
                plan_json = #{planJson},
                plan_approved = #{planApproved},
                current_run_id = #{runId},
                status = #{status},
                updated_at = NOW()
            WHERE id = #{id} AND workspace_id = #{workspaceId}
            """)
    int updatePlanProjection(
            @Param("id") String id,
            @Param("workspaceId") String workspaceId,
            @Param("revisionId") String revisionId,
            @Param("planJson") String planJson,
            @Param("planApproved") Integer planApproved,
            @Param("runId") String runId,
            @Param("status") String status);

    @Update("""
            UPDATE research_task SET status=#{status}, plan_approved=#{planApproved},
              current_node=#{currentNode}, progress=#{progress}, current_run_id=#{runId},
              current_plan_revision_id=#{revisionId}, updated_at=NOW()
            WHERE id=#{id} AND workspace_id=#{workspaceId}
            """)
    int updatePlanAction(@Param("id") String id, @Param("workspaceId") String workspaceId,
            @Param("status") String status, @Param("planApproved") Integer planApproved,
            @Param("currentNode") String currentNode, @Param("progress") int progress,
            @Param("runId") String runId, @Param("revisionId") String revisionId);

    /**
     * Start a new planning round after a revision request.
     *
     * <p>The task projection is deliberately cleared here.  The old immutable
     * revision remains available in {@code task_plan_revision}, but it must not
     * be exposed as the current plan while the replacement is being generated.</p>
     */
    @Update("""
            UPDATE research_task
            SET status = #{status},
                plan_json = NULL,
                plan_approved = NULL,
                current_plan_revision_id = NULL,
                current_node = #{currentNode},
                progress = #{progress},
                current_run_id = #{runId},
                updated_at = NOW()
            WHERE id = #{id} AND workspace_id = #{workspaceId}
            """)
    int resetPlanForRevision(@Param("id") String id, @Param("workspaceId") String workspaceId,
            @Param("status") String status, @Param("currentNode") String currentNode,
            @Param("progress") int progress, @Param("runId") String runId);

    /** Fail only the still-current dispatch run; an old retry must never overwrite a newer revision. */
    @Update("""
            UPDATE research_task
            SET status = 'FAILED', error_code = #{errorCode}, error_message = #{errorMessage},
                completed_at = NOW(), updated_at = NOW()
            WHERE id = #{id} AND workspace_id = #{workspaceId} AND current_run_id = #{runId}
              AND status IN ('PLANNING', 'RUNNING', 'WAITING_APPROVAL')
            """)
    int failDispatchIfCurrentRun(@Param("id") String id, @Param("workspaceId") String workspaceId,
            @Param("runId") String runId, @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage);

    /** 删除任务的图执行 Checkpoint 索引，必须先于任务主记录删除。 */
    @Delete("DELETE FROM task_checkpoint WHERE task_id = #{taskId}")
    int deleteCheckpointsByTaskId(@Param("taskId") String taskId);

    /** 删除时再次限定工作空间，避免跨空间误删。 */
    @Delete("DELETE FROM research_task WHERE id = #{taskId} AND workspace_id = #{workspaceId}")
    int deleteByIdAndWorkspace(@Param("taskId") String taskId, @Param("workspaceId") String workspaceId);
}
