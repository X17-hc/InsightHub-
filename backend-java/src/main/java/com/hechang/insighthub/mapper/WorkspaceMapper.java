package com.hechang.insighthub.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.hechang.insighthub.model.entity.Workspace;
import com.mybatisflex.core.BaseMapper;

/**
 * 工作空间 Mapper。
 */
@Mapper
public interface WorkspaceMapper extends BaseMapper<Workspace> {

    /**
     * 查询用户所属的正常状态工作空间（JOIN workspace_member）。
     *
     * @param userId 用户 ID
     * @return 工作空间列表
     */
    @Select("""
            SELECT w.id AS id,
                   w.name AS name,
                   w.description AS description,
                   w.owner_id AS ownerId,
                   w.max_concurrent_tasks AS maxConcurrentTasks,
                   w.monthly_token_quota AS monthlyTokenQuota,
                   w.status AS status,
                   w.created_at AS createdAt,
                   w.updated_at AS updatedAt
            FROM workspace w
            INNER JOIN workspace_member m ON m.workspace_id = w.id
            WHERE m.user_id = #{userId} AND w.status = 1
            ORDER BY w.created_at DESC
            """)
    List<Workspace> listByUserId(@Param("userId") String userId);

    /** 查询工作空间最大并发任务数 */
    @Select("SELECT max_concurrent_tasks AS maxConcurrentTasks FROM workspace WHERE id = #{workspaceId}")
    Integer selectMaxConcurrentTasks(@Param("workspaceId") String workspaceId);
}
