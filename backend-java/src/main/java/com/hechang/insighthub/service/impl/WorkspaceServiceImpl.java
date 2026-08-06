package com.hechang.insighthub.service.impl;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hechang.insighthub.exception.BusinessException;
import com.hechang.insighthub.mapper.SysUserMapper;
import com.hechang.insighthub.mapper.WorkspaceMapper;
import com.hechang.insighthub.mapper.WorkspaceMemberMapper;
import com.hechang.insighthub.model.dto.workspace.AddMemberRequest;
import com.hechang.insighthub.model.dto.workspace.CreateWorkspaceRequest;
import com.hechang.insighthub.model.dto.workspace.MemberResponse;
import com.hechang.insighthub.model.dto.workspace.WorkspaceResponse;
import com.hechang.insighthub.model.entity.Workspace;
import com.hechang.insighthub.model.entity.WorkspaceMember;
import com.hechang.insighthub.model.enums.WorkspaceRole;
import com.hechang.insighthub.security.SecurityUtils;
import com.hechang.insighthub.service.WorkspaceAccessService;
import com.hechang.insighthub.service.WorkspaceService;
import com.mybatisflex.spring.service.impl.ServiceImpl;

/**
 * 工作空间与成员业务实现。
 */
@Service
public class WorkspaceServiceImpl extends ServiceImpl<WorkspaceMapper, Workspace> implements WorkspaceService {

    private final WorkspaceAccessService accessService;
    private final WorkspaceMemberMapper memberMapper;
    private final SysUserMapper userMapper;

    public WorkspaceServiceImpl(
            WorkspaceAccessService accessService,
            WorkspaceMemberMapper memberMapper,
            SysUserMapper userMapper) {
        this.accessService = accessService;
        this.memberMapper = memberMapper;
        this.userMapper = userMapper;
    }

    @Override
    @Transactional
    public WorkspaceResponse create(CreateWorkspaceRequest request) {
        String userId = SecurityUtils.requireUserId();
        String workspaceId = "workspace-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setName(request.getName());
        workspace.setDescription(request.getDescription());
        workspace.setOwnerId(userId);
        workspace.setMaxConcurrentTasks(3);
        workspace.setMonthlyTokenQuota(1_000_000L);
        workspace.setStatus(1);
        save(workspace);

        WorkspaceMember member = new WorkspaceMember();
        member.setId("wm-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        member.setWorkspaceId(workspaceId);
        member.setUserId(userId);
        member.setRole(WorkspaceRole.OWNER.name());
        memberMapper.insert(member);

        return toResponse(getById(workspaceId));
    }

    @Override
    public List<WorkspaceResponse> listMine() {
        String userId = SecurityUtils.requireUserId();
        return mapper.listByUserId(userId).stream().map(this::toResponse).toList();
    }

    @Override
    public WorkspaceResponse get(String workspaceId) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);
        Workspace workspace = getById(workspaceId);
        if (workspace == null) {
            throw BusinessException.notFound("workspace not found");
        }
        return toResponse(workspace);
    }

    @Override
    public List<MemberResponse> listMembers(String workspaceId) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);
        return memberMapper.listMembers(workspaceId);
    }

    @Override
    @Transactional
    public MemberResponse addMember(String workspaceId, AddMemberRequest request) {
        String actorId = SecurityUtils.requireUserId();
        WorkspaceRole actorRole = accessService.requireAdmin(workspaceId, actorId);
        if (userMapper.selectOneById(request.getUserId()) == null) {
            throw BusinessException.notFound("user not found");
        }
        if (memberMapper.countByWorkspaceAndUser(workspaceId, request.getUserId()) > 0) {
            throw BusinessException.conflict("MEMBER_EXISTS", "user already in workspace");
        }
        WorkspaceRole role;
        try {
            role = WorkspaceRole.from(request.getRole() == null ? "MEMBER" : request.getRole());
        } catch (Exception ex) {
            throw BusinessException.badRequest("INVALID_ROLE", "role must be OWNER/ADMIN/MEMBER");
        }
        // 仅 OWNER 可授予 OWNER，防止 ADMIN 提权
        if (role == WorkspaceRole.OWNER && actorRole != WorkspaceRole.OWNER) {
            throw BusinessException.forbidden("only owner can grant OWNER role");
        }
        WorkspaceMember member = new WorkspaceMember();
        member.setId("wm-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        member.setWorkspaceId(workspaceId);
        member.setUserId(request.getUserId());
        member.setRole(role.name());
        memberMapper.insert(member);

        return listMembers(workspaceId).stream()
                .filter(m -> m.getUserId().equals(request.getUserId()))
                .findFirst()
                .orElseThrow();
    }

    @Override
    @Transactional
    public void removeMember(String workspaceId, String targetUserId) {
        String actorId = SecurityUtils.requireUserId();
        accessService.requireAdmin(workspaceId, actorId);
        String role = memberMapper.selectRole(workspaceId, targetUserId);
        if (role == null) {
            throw BusinessException.notFound("member not found");
        }
        if ("OWNER".equals(role) && memberMapper.countOwners(workspaceId) <= 1) {
            throw BusinessException.conflict("LAST_OWNER", "cannot remove the last owner");
        }
        memberMapper.deleteByWorkspaceAndUser(workspaceId, targetUserId);
    }

    private WorkspaceResponse toResponse(Workspace row) {
        return new WorkspaceResponse(
                row.getId(),
                row.getName(),
                row.getDescription(),
                row.getOwnerId(),
                row.getStatus() == null ? 0 : row.getStatus());
    }
}
