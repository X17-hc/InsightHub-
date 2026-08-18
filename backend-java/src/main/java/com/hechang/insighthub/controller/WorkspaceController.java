package com.hechang.insighthub.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hechang.insighthub.common.BaseResponse;
import com.hechang.insighthub.common.ResultUtils;
import com.hechang.insighthub.model.dto.workspace.AddMemberRequest;
import com.hechang.insighthub.model.dto.workspace.CreateWorkspaceRequest;
import com.hechang.insighthub.model.dto.workspace.MemberResponse;
import com.hechang.insighthub.model.dto.workspace.WorkspaceResponse;
import com.hechang.insighthub.service.WorkspaceService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 工作空间与成员 API。
 */
@RestController
@RequestMapping("/api/v1/workspaces")
@Tag(name = "Workspace")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;


    @PostMapping
    @Operation(summary = "创建工作空间")
    public BaseResponse<WorkspaceResponse> create(@Valid @RequestBody CreateWorkspaceRequest request) {
        return ResultUtils.success(workspaceService.create(request));
    }

    @GetMapping
    @Operation(summary = "我的工作空间列表")
    public BaseResponse<List<WorkspaceResponse>> list() {
        return ResultUtils.success(workspaceService.listMine());
    }

    @GetMapping("/{workspaceId}")
    @Operation(summary = "工作空间详情")
    public BaseResponse<WorkspaceResponse> get(@PathVariable String workspaceId) {
        return ResultUtils.success(workspaceService.get(workspaceId));
    }

    @GetMapping("/{workspaceId}/members")
    @Operation(summary = "成员列表")
    public BaseResponse<List<MemberResponse>> members(@PathVariable String workspaceId) {
        return ResultUtils.success(workspaceService.listMembers(workspaceId));
    }

    @PostMapping("/{workspaceId}/members")
    @Operation(summary = "添加成员")
    public BaseResponse<MemberResponse> addMember(
            @PathVariable String workspaceId,
            @Valid @RequestBody AddMemberRequest request) {
        return ResultUtils.success(workspaceService.addMember(workspaceId, request));
    }

    @DeleteMapping("/{workspaceId}/members/{userId}")
    @Operation(summary = "移除成员")
    public BaseResponse<Void> removeMember(
            @PathVariable String workspaceId,
            @PathVariable String userId) {
        workspaceService.removeMember(workspaceId, userId);
        return ResultUtils.success(null);
    }
}
