package com.hechang.insighthub.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import com.hechang.insighthub.model.entity.TaskDispatchOutbox;
import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;

public interface TaskDispatchOutboxMapper extends BaseMapper<TaskDispatchOutbox> {
    @Select("SELECT * FROM task_dispatch_outbox WHERE status IN ('PENDING','RETRY') AND (next_attempt_at IS NULL OR next_attempt_at <= NOW()) ORDER BY created_at LIMIT #{limit}")
    List<TaskDispatchOutbox> findReady(@Param("limit") int limit);

    @Update("UPDATE task_dispatch_outbox SET status='DISPATCHING', attempt_count=attempt_count+1, updated_at=NOW() WHERE id=#{id} AND status IN ('PENDING','RETRY')")
    int claim(@Param("id") String id);

    /** Recover commands claimed by a process which terminated before dispatch completion. */
    @Update("UPDATE task_dispatch_outbox SET status='RETRY', next_attempt_at=NOW(), updated_at=NOW() WHERE status='DISPATCHING'")
    int recoverInFlight();

    @Update("UPDATE task_dispatch_outbox SET status='DISPATCHED', last_error=NULL, updated_at=NOW() WHERE id=#{id}")
    int markDispatched(@Param("id") String id);

    @Update("UPDATE task_dispatch_outbox SET status='RETRY', last_error=#{error}, next_attempt_at=#{nextAttemptAt}, updated_at=NOW() WHERE id=#{id}")
    int markRetry(@Param("id") String id, @Param("error") String error, @Param("nextAttemptAt") LocalDateTime nextAttemptAt);

    @Update("UPDATE task_dispatch_outbox SET status='FAILED', last_error=#{error}, updated_at=NOW() WHERE id=#{id}")
    int markFailed(@Param("id") String id, @Param("error") String error);

    default int deleteByTaskId(String taskId) {
        return deleteByQuery(QueryWrapper.create().eq(TaskDispatchOutbox::getTaskId, taskId));
    }
}
