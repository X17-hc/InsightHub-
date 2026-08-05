package com.insighthub.workspace;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.insighthub.workspace.dto.AddMemberRequest;
import com.insighthub.workspace.dto.CreateWorkspaceRequest;
import com.insighthub.workspace.dto.MemberResponse;
import com.insighthub.workspace.dto.WorkspaceResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * 工作空间与成员 API。
 */
@RestController
@RequestMapping("/api/v1/workspaces")
@Validated
@Tag(name = "Workspace")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @PostMapping
    @Operation(summary = "创建工作空间")
    public ResponseEntity<WorkspaceResponse> create(@Valid @RequestBody CreateWorkspaceRequest request) {
        return ResponseEntity.ok(workspaceService.create(request));
    }

    @GetMapping
    @Operation(summary = "我的工作空间列表")
    public ResponseEntity<List<WorkspaceResponse>> list() {
        return ResponseEntity.ok(workspaceService.listMine());
    }

    @GetMapping("/{workspaceId}")
    @Operation(summary = "工作空间详情")
    public ResponseEntity<WorkspaceResponse> get(@PathVariable String workspaceId) {
        return ResponseEntity.ok(workspaceService.get(workspaceId));
    }

    @GetMapping("/{workspaceId}/members")
    @Operation(summary = "成员列表")
    public ResponseEntity<List<MemberResponse>> members(@PathVariable String workspaceId) {
        return ResponseEntity.ok(workspaceService.listMembers(workspaceId));
    }

    @PostMapping("/{workspaceId}/members")
    @Operation(summary = "添加成员")
    public ResponseEntity<MemberResponse> addMember(
            @PathVariable String workspaceId,
            @Valid @RequestBody AddMemberRequest request) {
        return ResponseEntity.ok(workspaceService.addMember(workspaceId, request));
    }

    @DeleteMapping("/{workspaceId}/members/{userId}")
    @Operation(summary = "移除成员")
    public ResponseEntity<Void> removeMember(
            @PathVariable String workspaceId,
            @PathVariable String userId) {
        workspaceService.removeMember(workspaceId, userId);
        return ResponseEntity.noContent().build();
    }
}
