package com.insighthub.workspace;

import org.springframework.stereotype.Service;

import com.insighthub.common.BusinessException;

/**
 * 工作空间成员 / 管理员权限校验。
 */
@Service
public class WorkspaceAccessService {

    private final WorkspaceRepository workspaceRepository;

    public WorkspaceAccessService(WorkspaceRepository workspaceRepository) {
        this.workspaceRepository = workspaceRepository;
    }

    /**
     * 要求当前用户为启用中工作空间的成员，返回角色。
     * 工作空间不存在 / 已禁用 / 非成员统一返回 403，降低枚举面。
     */
    public WorkspaceRole requireMember(String workspaceId, String userId) {
        var workspace = workspaceRepository.findById(workspaceId);
        if (workspace.isEmpty() || workspace.get().status() != 1) {
            throw BusinessException.forbidden("not a member of workspace");
        }
        return workspaceRepository.findMemberRole(workspaceId, userId)
                .map(WorkspaceRole::from)
                .orElseThrow(() -> BusinessException.forbidden("not a member of workspace"));
    }

    /**
     * 要求 OWNER 或 ADMIN。
     */
    public WorkspaceRole requireAdmin(String workspaceId, String userId) {
        WorkspaceRole role = requireMember(workspaceId, userId);
        if (!role.isAdminOrAbove()) {
            throw BusinessException.forbidden("admin or owner required");
        }
        return role;
    }
}
