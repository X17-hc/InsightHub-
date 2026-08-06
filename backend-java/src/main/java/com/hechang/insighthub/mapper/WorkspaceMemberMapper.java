package com.hechang.insighthub.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.hechang.insighthub.model.dto.workspace.MemberResponse;
import com.hechang.insighthub.model.entity.WorkspaceMember;
import com.mybatisflex.core.BaseMapper;

/**
 * 工作空间成员 Mapper。
 */
@Mapper
public interface WorkspaceMemberMapper extends BaseMapper<WorkspaceMember> {

    /** 查询成员角色 */
    @Select("""
            SELECT role FROM workspace_member
            WHERE workspace_id = #{workspaceId} AND user_id = #{userId}
            """)
    String selectRole(@Param("workspaceId") String workspaceId, @Param("userId") String userId);

    /** 统计工作空间内是否存在该用户 */
    @Select("""
            SELECT COUNT(*) FROM workspace_member
            WHERE workspace_id = #{workspaceId} AND user_id = #{userId}
            """)
    long countByWorkspaceAndUser(@Param("workspaceId") String workspaceId, @Param("userId") String userId);

    /** 统计 OWNER 数量（移除所有者前校验） */
    @Select("""
            SELECT COUNT(*) FROM workspace_member
            WHERE workspace_id = #{workspaceId} AND role = 'OWNER'
            """)
    long countOwners(@Param("workspaceId") String workspaceId);

    /** 按工作空间与用户删除成员关系 */
    @Delete("""
            DELETE FROM workspace_member
            WHERE workspace_id = #{workspaceId} AND user_id = #{userId}
            """)
    int deleteByWorkspaceAndUser(@Param("workspaceId") String workspaceId, @Param("userId") String userId);

    /**
     * 列出工作空间成员（JOIN sys_user，字段对齐 MemberResponse）。
     *
     * @param workspaceId 工作空间 ID
     * @return 成员响应列表
     */
    @Select("""
            SELECT m.id AS id,
                   m.workspace_id AS workspaceId,
                   m.user_id AS userId,
                   m.role AS role,
                   u.username AS username,
                   u.display_name AS displayName
            FROM workspace_member m
            INNER JOIN sys_user u ON u.id = m.user_id
            WHERE m.workspace_id = #{workspaceId}
            ORDER BY m.joined_at
            """)
    List<MemberResponse> listMembers(@Param("workspaceId") String workspaceId);
}
