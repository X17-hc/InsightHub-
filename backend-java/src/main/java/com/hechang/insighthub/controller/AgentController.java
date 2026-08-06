package com.hechang.insighthub.controller;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hechang.insighthub.common.BaseResponse;
import com.hechang.insighthub.common.ResultUtils;
import com.hechang.insighthub.model.dto.agent.AgentResponse;
import com.hechang.insighthub.model.dto.agent.CreateAgentRequest;
import com.hechang.insighthub.model.dto.agent.UpdateAgentRequest;
import com.hechang.insighthub.service.AgentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * 工作空间 Agent 配置 API。
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/agents")
@Validated
@Tag(name = "Agent")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping
    @Operation(summary = "Agent 列表")
    public BaseResponse<List<AgentResponse>> list(@PathVariable String workspaceId) {
        return ResultUtils.success(agentService.list(workspaceId));
    }

    @PostMapping
    @Operation(summary = "创建 Agent")
    public BaseResponse<AgentResponse> create(
            @PathVariable String workspaceId,
            @Valid @RequestBody CreateAgentRequest request) {
        return ResultUtils.success(agentService.create(workspaceId, request));
    }

    @PutMapping("/{agentId}")
    @Operation(summary = "更新 Agent")
    public BaseResponse<AgentResponse> update(
            @PathVariable String workspaceId,
            @PathVariable String agentId,
            @Valid @RequestBody UpdateAgentRequest request) {
        return ResultUtils.success(agentService.update(workspaceId, agentId, request));
    }

    @PostMapping("/{agentId}/enable")
    @Operation(summary = "启用 Agent")
    public BaseResponse<AgentResponse> enable(
            @PathVariable String workspaceId,
            @PathVariable String agentId) {
        return ResultUtils.success(agentService.enable(workspaceId, agentId, true));
    }

    @PostMapping("/{agentId}/disable")
    @Operation(summary = "停用 Agent")
    public BaseResponse<AgentResponse> disable(
            @PathVariable String workspaceId,
            @PathVariable String agentId) {
        return ResultUtils.success(agentService.enable(workspaceId, agentId, false));
    }
}
