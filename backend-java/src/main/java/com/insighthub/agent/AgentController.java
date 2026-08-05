package com.insighthub.agent;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.insighthub.agent.dto.AgentResponse;
import com.insighthub.agent.dto.CreateAgentRequest;
import com.insighthub.agent.dto.UpdateAgentRequest;

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
    public ResponseEntity<List<AgentResponse>> list(@PathVariable String workspaceId) {
        return ResponseEntity.ok(agentService.list(workspaceId));
    }

    @PostMapping
    @Operation(summary = "创建 Agent")
    public ResponseEntity<AgentResponse> create(
            @PathVariable String workspaceId,
            @Valid @RequestBody CreateAgentRequest request) {
        return ResponseEntity.ok(agentService.create(workspaceId, request));
    }

    @PutMapping("/{agentId}")
    @Operation(summary = "更新 Agent")
    public ResponseEntity<AgentResponse> update(
            @PathVariable String workspaceId,
            @PathVariable String agentId,
            @Valid @RequestBody UpdateAgentRequest request) {
        return ResponseEntity.ok(agentService.update(workspaceId, agentId, request));
    }

    @PostMapping("/{agentId}/enable")
    @Operation(summary = "启用 Agent")
    public ResponseEntity<AgentResponse> enable(
            @PathVariable String workspaceId,
            @PathVariable String agentId) {
        return ResponseEntity.ok(agentService.enable(workspaceId, agentId, true));
    }

    @PostMapping("/{agentId}/disable")
    @Operation(summary = "停用 Agent")
    public ResponseEntity<AgentResponse> disable(
            @PathVariable String workspaceId,
            @PathVariable String agentId) {
        return ResponseEntity.ok(agentService.enable(workspaceId, agentId, false));
    }
}
