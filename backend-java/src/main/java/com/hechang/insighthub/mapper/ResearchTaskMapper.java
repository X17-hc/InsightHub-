package com.hechang.insighthub.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.hechang.insighthub.model.entity.ResearchTask;
import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;

/**
 * 研究任务 Mapper。
 */
@Mapper
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
                   knowledge_base_ids AS knowledgeBaseIds,
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
                completed_at = NULL,
                updated_at = NOW()
            WHERE id = #{id} AND workspace_id = #{workspaceId}
            """)
    int prepareRetry(
            @Param("id") String id,
            @Param("workspaceId") String workspaceId,
            @Param("runId") String runId);

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
}
