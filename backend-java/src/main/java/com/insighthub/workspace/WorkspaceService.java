package com.insighthub.workspace;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.insighthub.auth.UserRepository;
import com.insighthub.common.BusinessException;
import com.insighthub.security.SecurityUtils;
import com.insighthub.workspace.dto.AddMemberRequest;
import com.insighthub.workspace.dto.CreateWorkspaceRequest;
import com.insighthub.workspace.dto.MemberResponse;
import com.insighthub.workspace.dto.WorkspaceResponse;

/**
 * 工作空间与成员业务。
 */
@Service
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceAccessService accessService;
    private final UserRepository userRepository;

    public WorkspaceService(
            WorkspaceRepository workspaceRepository,
            WorkspaceAccessService accessService,
            UserRepository userRepository) {
        this.workspaceRepository = workspaceRepository;
        this.accessService = accessService;
        this.userRepository = userRepository;
    }

    @Transactional
    public WorkspaceResponse create(CreateWorkspaceRequest request) {
        String userId = SecurityUtils.requireUserId();
        String workspaceId = "workspace-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        workspaceRepository.insertWorkspace(
                workspaceId,
                request.getName(),
                request.getDescription(),
                userId);
        workspaceRepository.insertMember(
                "wm-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                workspaceId,
                userId,
                WorkspaceRole.OWNER.name());
        return toResponse(workspaceRepository.findById(workspaceId).orElseThrow());
    }

    public List<WorkspaceResponse> listMine() {
        String userId = SecurityUtils.requireUserId();
        return workspaceRepository.listByUser(userId).stream().map(this::toResponse).toList();
    }

    public WorkspaceResponse get(String workspaceId) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);
        return toResponse(workspaceRepository.findById(workspaceId).orElseThrow());
    }

    public List<MemberResponse> listMembers(String workspaceId) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);
        return workspaceRepository.listMembers(workspaceId).stream().map(this::toMember).toList();
    }

    @Transactional
    public MemberResponse addMember(String workspaceId, AddMemberRequest request) {
        String actorId = SecurityUtils.requireUserId();
        WorkspaceRole actorRole = accessService.requireAdmin(workspaceId, actorId);
        userRepository.findById(request.getUserId())
                .orElseThrow(() -> BusinessException.notFound("user not found"));
        if (workspaceRepository.memberExists(workspaceId, request.getUserId())) {
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
        String memberId = "wm-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        workspaceRepository.insertMember(memberId, workspaceId, request.getUserId(), role.name());
        return listMembers(workspaceId).stream()
                .filter(m -> m.getUserId().equals(request.getUserId()))
                .findFirst()
                .orElseThrow();
    }

    @Transactional
    public void removeMember(String workspaceId, String targetUserId) {
        String actorId = SecurityUtils.requireUserId();
        accessService.requireAdmin(workspaceId, actorId);
        String role = workspaceRepository.findMemberRole(workspaceId, targetUserId)
                .orElseThrow(() -> BusinessException.notFound("member not found"));
        if ("OWNER".equals(role) && workspaceRepository.countOwners(workspaceId) <= 1) {
            throw BusinessException.conflict("LAST_OWNER", "cannot remove the last owner");
        }
        workspaceRepository.deleteMember(workspaceId, targetUserId);
    }

    private WorkspaceResponse toResponse(WorkspaceRepository.WorkspaceRow row) {
        return new WorkspaceResponse(row.id(), row.name(), row.description(), row.ownerId(), row.status());
    }

    private MemberResponse toMember(WorkspaceRepository.MemberRow row) {
        return new MemberResponse(
                row.id(), row.workspaceId(), row.userId(), row.role(), row.username(), row.displayName());
    }
}
