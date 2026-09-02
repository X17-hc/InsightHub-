package com.hechang.insighthub.mapper;

import java.util.List;

import com.hechang.insighthub.model.entity.ResearchTask;
import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.update.UpdateWrapper;
import com.mybatisflex.core.util.UpdateEntity;

/**
 * 研究任务 Mapper。
 * 部分字段更新用 {@link UpdateEntity}（调用了 setter 才会写入，含显式 null）；
 * 附加 WHERE 用 {@link BaseMapper#updateByQuery}；只有 SQL 表达式才 {@link UpdateWrapper#setRaw}。
 */
public interface ResearchTaskMapper extends BaseMapper<ResearchTask> {

    default ResearchTask findByIdAndWorkspace(String id, String workspaceId) {
        return selectOneByQuery(QueryWrapper.create()
                .eq(ResearchTask::getId, id)
                .eq(ResearchTask::getWorkspaceId, workspaceId));
    }

    default ResearchTask findByIdAndWorkspaceForUpdate(String id, String workspaceId) {
        // 报告版本及任务投影写入前锁定任务行；事务外调用会失去并发保护。
        return selectOneByQuery(QueryWrapper.create()
                .eq(ResearchTask::getId, id)
                .eq(ResearchTask::getWorkspaceId, workspaceId)
                .forUpdate());
    }

    default List<ResearchTask> listByWorkspace(String workspaceId) {
        return selectListByQuery(QueryWrapper.create()
                .eq(ResearchTask::getWorkspaceId, workspaceId)
                .orderBy(ResearchTask::getCreatedAt, false));
    }

    default int deleteByIdAndWorkspace(String taskId, String workspaceId) {
        // workspaceId 必须进入 DELETE 条件，防止已知 taskId 被跨租户删除。
        return deleteByQuery(QueryWrapper.create()
                .eq(ResearchTask::getId, taskId)
                .eq(ResearchTask::getWorkspaceId, workspaceId));
    }

    default int deleteCheckpointsByTaskId(String taskId) {
        return Db.deleteByQuery("task_checkpoint", QueryWrapper.create().where("task_id = ?", taskId));
    }

    default int clearCurrentPlanRevision(String taskId, String workspaceId) {
        // 删除任务前显式清空当前修订外键；UpdateEntity 能表达“写入 null”。
        ResearchTask row = UpdateEntity.of(ResearchTask.class);
        row.setCurrentPlanRevisionId(null);
        return updateByQuery(row, QueryWrapper.create()
                .eq(ResearchTask::getId, taskId)
                .eq(ResearchTask::getWorkspaceId, workspaceId));
    }

    default int updateQuality(
            String id, String workspaceId, String qualityStatus, String qualitySummary,
            int verifiedCount, int totalCount) {
        ResearchTask row = UpdateEntity.of(ResearchTask.class);
        row.setQualityStatus(qualityStatus);
        row.setQualitySummary(qualitySummary);
        row.setVerifiedCitationCount(verifiedCount);
        row.setTotalCitationCount(totalCount);
        return updateByQuery(row, QueryWrapper.create()
                .eq(ResearchTask::getId, id)
                .eq(ResearchTask::getWorkspaceId, workspaceId));
    }

    default int updateStatus(String id, String workspaceId, String status, Integer progress, String currentNode) {
        ResearchTask row = UpdateEntity.of(ResearchTask.class);
        row.setStatus(status);
        if (progress != null) {
            row.setProgress(progress);
        }
        if (currentNode != null) {
            row.setCurrentNode(currentNode);
        }
        UpdateWrapper.of(row).setRaw(ResearchTask::getStartedAt, "COALESCE(started_at, NOW())");
        return updateByQuery(row, QueryWrapper.create()
                .eq(ResearchTask::getId, id)
                .eq(ResearchTask::getWorkspaceId, workspaceId));
    }

    default int updateStatusIfCurrent(
            String id, String workspaceId, String fromStatus, String toStatus,
            Integer progress, String currentNode) {
        // fromStatus 是 CAS 条件；返回 0 表示状态已被其他控制请求推进。
        ResearchTask row = UpdateEntity.of(ResearchTask.class);
        row.setStatus(toStatus);
        if (progress != null) {
            row.setProgress(progress);
        }
        if (currentNode != null) {
            row.setCurrentNode(currentNode);
        }
        UpdateWrapper.of(row).setRaw(ResearchTask::getStartedAt, "COALESCE(started_at, NOW())");
        return updateByQuery(row, QueryWrapper.create()
                .eq(ResearchTask::getId, id)
                .eq(ResearchTask::getWorkspaceId, workspaceId)
                .eq(ResearchTask::getStatus, fromStatus));
    }

    default int updateTaskFinished(
            String id, String workspaceId, String status, String runId,
            String errorCode, String errorMessage) {
        ResearchTask row = UpdateEntity.of(ResearchTask.class);
        row.setStatus(status);
        row.setCurrentRunId(runId);
        row.setErrorCode(errorCode);
        row.setErrorMessage(errorMessage);
        if ("COMPLETED".equals(status)) {
            row.setProgress(100);
        }
        UpdateWrapper.of(row)
                .setRaw(ResearchTask::getStartedAt, "COALESCE(started_at, NOW())")
                .setRaw(ResearchTask::getCompletedAt, "NOW()");
        return updateByQuery(row, QueryWrapper.create()
                .eq(ResearchTask::getId, id)
                .eq(ResearchTask::getWorkspaceId, workspaceId));
    }

    default int prepareRetry(String id, String workspaceId, String runId) {
        ResearchTask row = UpdateEntity.of(ResearchTask.class);
        row.setCurrentRunId(runId);
        row.setErrorCode(null);
        row.setErrorMessage(null);
        row.setQualityStatus("PENDING");
        row.setQualitySummary(null);
        row.setVerifiedCitationCount(0);
        row.setTotalCitationCount(0);
        row.setCompletedAt(null);
        return updateByQuery(row, QueryWrapper.create()
                .eq(ResearchTask::getId, id)
                .eq(ResearchTask::getWorkspaceId, workspaceId));
    }

    default int updatePlanProjection(
            String id, String workspaceId, String revisionId, String planJson,
            Integer planApproved, String runId, String status) {
        ResearchTask row = UpdateEntity.of(ResearchTask.class);
        row.setCurrentPlanRevisionId(revisionId);
        row.setPlanJson(planJson);
        row.setPlanApproved(planApproved);
        row.setCurrentRunId(runId);
        row.setStatus(status);
        return updateByQuery(row, QueryWrapper.create()
                .eq(ResearchTask::getId, id)
                .eq(ResearchTask::getWorkspaceId, workspaceId));
    }

    default int updatePlanAction(
            String id, String workspaceId, String status, Integer planApproved,
            String currentNode, int progress, String runId, String revisionId) {
        ResearchTask row = UpdateEntity.of(ResearchTask.class);
        row.setStatus(status);
        row.setPlanApproved(planApproved);
        row.setCurrentNode(currentNode);
        row.setProgress(progress);
        row.setCurrentRunId(runId);
        row.setCurrentPlanRevisionId(revisionId);
        return updateByQuery(row, QueryWrapper.create()
                .eq(ResearchTask::getId, id)
                .eq(ResearchTask::getWorkspaceId, workspaceId));
    }

    default int resetPlanForRevision(
            String id, String workspaceId, String status, String currentNode, int progress, String runId) {
        ResearchTask row = UpdateEntity.of(ResearchTask.class);
        row.setStatus(status);
        row.setPlanJson(null);
        row.setPlanApproved(null);
        row.setCurrentPlanRevisionId(null);
        row.setCurrentNode(currentNode);
        row.setProgress(progress);
        row.setCurrentRunId(runId);
        return updateByQuery(row, QueryWrapper.create()
                .eq(ResearchTask::getId, id)
                .eq(ResearchTask::getWorkspaceId, workspaceId));
    }

    default int failDispatchIfCurrentRun(
            String id, String workspaceId, String runId, String errorCode, String errorMessage) {
        // 同时限定 runId 和活动状态，迟到的旧派发失败不得覆盖新一轮重试。
        ResearchTask row = UpdateEntity.of(ResearchTask.class);
        row.setStatus("FAILED");
        row.setErrorCode(errorCode);
        row.setErrorMessage(errorMessage);
        UpdateWrapper.of(row).setRaw(ResearchTask::getCompletedAt, "NOW()");
        return updateByQuery(row, QueryWrapper.create()
                .eq(ResearchTask::getId, id)
                .eq(ResearchTask::getWorkspaceId, workspaceId)
                .eq(ResearchTask::getCurrentRunId, runId)
                .in(ResearchTask::getStatus, "PLANNING", "RUNNING", "WAITING_APPROVAL"));
    }
}
