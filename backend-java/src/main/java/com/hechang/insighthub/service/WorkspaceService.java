package com.hechang.insighthub.service;

import java.util.List;

import com.hechang.insighthub.model.dto.workspace.AddMemberRequest;
import com.hechang.insighthub.model.dto.workspace.CreateWorkspaceRequest;
import com.hechang.insighthub.model.dto.workspace.MemberResponse;
import com.hechang.insighthub.model.dto.workspace.WorkspaceResponse;
import com.hechang.insighthub.model.entity.Workspace;
import com.mybatisflex.core.service.IService;

/**
 * 工作空间与成员业务。
 */
public interface WorkspaceService extends IService<Workspace> {

    /** 创建工作空间并将当前用户设为 OWNER */
    WorkspaceResponse create(CreateWorkspaceRequest request);

    /** 当前用户所属工作空间列表 */
    List<WorkspaceResponse> listMine();

    /** 工作空间详情（需为成员） */
    WorkspaceResponse get(String workspaceId);

    /** 成员列表 */
    List<MemberResponse> listMembers(String workspaceId);

    /** 添加成员（ADMIN/OWNER） */
    MemberResponse addMember(String workspaceId, AddMemberRequest request);

    /** 移除成员（ADMIN/OWNER；不可移除最后一位 OWNER） */
    void removeMember(String workspaceId, String targetUserId);
}
