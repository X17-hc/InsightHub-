package com.hechang.insighthub.service;

import com.hechang.insighthub.model.enums.WorkspaceRole;
import com.hechang.insighthub.security.SecurityUtils;

/**
 * 工作空间成员 / 管理员权限校验。
 */
public interface WorkspaceAccessService {

    /**
     * 要求当前用户为启用中工作空间的成员，返回角色。
     * 工作空间不存在 / 已禁用 / 非成员统一返回 403，降低枚举面。
     *
     * @param workspaceId 工作空间 ID
     * @param userId      用户 ID
     * @return 成员角色
     */
    WorkspaceRole requireMember(String workspaceId, String userId);

    /**
     * 要求 OWNER 或 ADMIN。
     *
     * @param workspaceId 工作空间 ID
     * @param userId      用户 ID
     * @return 成员角色
     */
    WorkspaceRole requireAdmin(String workspaceId, String userId);

    /** 校验当前登录用户的成员身份，并携带身份与角色返回给调用方。 */
    default CurrentWorkspaceAccess requireCurrentMember(String workspaceId) {
        String userId = SecurityUtils.requireUserId();
        return new CurrentWorkspaceAccess(userId, requireMember(workspaceId, userId));
    }

    /** 校验当前登录用户具备管理员身份，并携带身份与角色返回给调用方。 */
    default CurrentWorkspaceAccess requireCurrentAdmin(String workspaceId) {
        String userId = SecurityUtils.requireUserId();
        return new CurrentWorkspaceAccess(userId, requireAdmin(workspaceId, userId));
    }
}
