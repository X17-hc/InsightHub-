package com.hechang.insighthub.service.impl;

import org.springframework.stereotype.Service;

import com.hechang.insighthub.exception.BusinessException;
import com.hechang.insighthub.mapper.WorkspaceMapper;
import com.hechang.insighthub.mapper.WorkspaceMemberMapper;
import com.hechang.insighthub.model.entity.Workspace;
import com.hechang.insighthub.model.entity.WorkspaceMember;
import com.hechang.insighthub.model.enums.WorkspaceRole;
import com.hechang.insighthub.service.WorkspaceAccessService;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;

/**
 * 工作空间成员 / 管理员权限校验实现。
 */
@Service
@RequiredArgsConstructor
public class WorkspaceAccessServiceImpl implements WorkspaceAccessService {

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper memberMapper;

    @Override
    public WorkspaceRole requireMember(String workspaceId, String userId) {
        Workspace workspace = workspaceMapper.selectOneById(workspaceId);
        if (workspace == null || workspace.getStatus() == null || workspace.getStatus() != 1) {
            throw BusinessException.forbidden("not a member of workspace");
        }
        WorkspaceMember member = memberMapper.findByWorkspaceAndUser(workspaceId, userId);
        if (member == null || member.getRole() == null || member.getRole().isBlank()) {
            throw BusinessException.forbidden("not a member of workspace");
        }
        return WorkspaceRole.from(member.getRole());
    }

    @Override
    public WorkspaceRole requireAdmin(String workspaceId, String userId) {
        WorkspaceRole role = requireMember(workspaceId, userId);
        if (!role.isAdminOrAbove()) {
            throw BusinessException.forbidden("admin or owner required");
        }
        return role;
    }
}
