package com.hechang.insighthub.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import com.hechang.insighthub.model.entity.TaskPlanRevision;
import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;

public interface TaskPlanRevisionMapper extends BaseMapper<TaskPlanRevision> {
    /**
     * Returns the newest immutable plan revision for a task.
     *
     * <p>The task row is locked by the calling application service before this
     * query is used, so {@code max + 1} allocation remains serialized per task.</p>
     */
    default TaskPlanRevision findLatestByTask(String taskId) {
        return selectOneByQuery(QueryWrapper.create()
                .eq(TaskPlanRevision::getTaskId, taskId)
                .orderBy(TaskPlanRevision::getRevisionNo, false)
                .limit(1));
    }

    @Update("UPDATE task_plan_revision SET status='APPROVED', approved_by=#{userId}, approval_remark=#{remark}, approved_at=#{approvedAt} WHERE id=#{id} AND status='PENDING'")
    int approvePending(@Param("id") String id, @Param("userId") String userId,
            @Param("remark") String remark, @Param("approvedAt") LocalDateTime approvedAt);

    @Update("UPDATE task_plan_revision SET status='SUPERSEDED' WHERE id=#{id} AND status='PENDING'")
    int supersedePending(@Param("id") String id);

    default int deleteByTaskId(String taskId) {
        return deleteByQuery(QueryWrapper.create().eq(TaskPlanRevision::getTaskId, taskId));
    }
}
