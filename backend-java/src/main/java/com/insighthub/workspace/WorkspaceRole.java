package com.insighthub.workspace;

/**
 * 工作空间 RBAC 角色。
 */
public enum WorkspaceRole {
    OWNER,
    ADMIN,
    MEMBER;

    public boolean isAdminOrAbove() {
        return this == OWNER || this == ADMIN;
    }

    public static WorkspaceRole from(String raw) {
        return WorkspaceRole.valueOf(raw.trim().toUpperCase());
    }
}
