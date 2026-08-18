package com.hechang.insighthub.service;

import com.hechang.insighthub.model.enums.WorkspaceRole;

/** 当前登录用户在指定工作空间中的已校验访问上下文。 */
public record CurrentWorkspaceAccess(String userId, WorkspaceRole role) {
}
