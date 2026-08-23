package com.hechang.insighthub.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.hechang.insighthub.model.dto.workspace.MemberResponse;
import com.hechang.insighthub.model.entity.WorkspaceMember;
import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;

/**
 * 工作空间成员 Mapper。
 */
public interface WorkspaceMemberMapper extends BaseMapper<WorkspaceMember> {
    default WorkspaceMember findByWorkspaceAndUser(String workspaceId, String userId) {
        return selectOneByQuery(QueryWrapper.create()
                .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                .eq(WorkspaceMember::getUserId, userId));
    }

    default boolean existsByWorkspaceAndUser(String workspaceId, String userId) {
        return selectCountByQuery(QueryWrapper.create()
                .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                .eq(WorkspaceMember::getUserId, userId)) > 0;
    }

    default long countByWorkspaceAndRole(String workspaceId, String role) {
        return selectCountByQuery(QueryWrapper.create()
                .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                .eq(WorkspaceMember::getRole, role));
    }
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
