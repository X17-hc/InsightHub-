package com.hechang.insighthub.service;

import com.hechang.insighthub.model.enums.WorkspaceRole;

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
}
