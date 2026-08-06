package com.hechang.insighthub.service.impl;

import org.springframework.stereotype.Service;

import com.hechang.insighthub.exception.BusinessException;
import com.hechang.insighthub.mapper.WorkspaceMapper;
import com.hechang.insighthub.mapper.WorkspaceMemberMapper;
import com.hechang.insighthub.model.entity.Workspace;
import com.hechang.insighthub.model.enums.WorkspaceRole;
import com.hechang.insighthub.service.WorkspaceAccessService;

/**
 * 工作空间成员 / 管理员权限校验实现。
 */
@Service
public class WorkspaceAccessServiceImpl implements WorkspaceAccessService {

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper memberMapper;

    public WorkspaceAccessServiceImpl(WorkspaceMapper workspaceMapper, WorkspaceMemberMapper memberMapper) {
        this.workspaceMapper = workspaceMapper;
        this.memberMapper = memberMapper;
    }

    @Override
    public WorkspaceRole requireMember(String workspaceId, String userId) {
        Workspace workspace = workspaceMapper.selectOneById(workspaceId);
        if (workspace == null || workspace.getStatus() == null || workspace.getStatus() != 1) {
            throw BusinessException.forbidden("not a member of workspace");
        }
        String role = memberMapper.selectRole(workspaceId, userId);
        if (role == null || role.isBlank()) {
            throw BusinessException.forbidden("not a member of workspace");
        }
        return WorkspaceRole.from(role);
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
