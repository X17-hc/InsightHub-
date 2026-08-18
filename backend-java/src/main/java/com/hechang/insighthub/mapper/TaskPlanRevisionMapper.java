package com.hechang.insighthub.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import com.hechang.insighthub.model.entity.TaskPlanRevision;
import com.mybatisflex.core.BaseMapper;

public interface TaskPlanRevisionMapper extends BaseMapper<TaskPlanRevision> {
    @Update("UPDATE task_plan_revision SET status='APPROVED', approved_by=#{userId}, approved_at=#{approvedAt} WHERE id=#{id} AND status='PENDING'")
    int approvePending(@Param("id") String id, @Param("userId") String userId, @Param("approvedAt") LocalDateTime approvedAt);

    @Update("UPDATE task_plan_revision SET status='SUPERSEDED' WHERE id=#{id} AND status='PENDING'")
    int supersedePending(@Param("id") String id);
}
