package com.insighthub.web;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.insighthub.task.ResearchTaskService;
import com.insighthub.task.dto.TaskSummaryResponse;
import com.insighthub.web.dto.AgentTaskResponseDto;
import com.insighthub.web.dto.CreateResearchTaskRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * 工作空间内研究任务 API（需 JWT）。
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/research/tasks")
@Validated
@Tag(name = "ResearchTask")
@SecurityRequirement(name = "BearerAuth")
public class ResearchTaskController {

    private final ResearchTaskService researchTaskService;

    public ResearchTaskController(ResearchTaskService researchTaskService) {
        this.researchTaskService = researchTaskService;
    }

    @PostMapping
    @Operation(summary = "创建并同步执行研究任务")
    public ResponseEntity<AgentTaskResponseDto> create(
            @PathVariable String workspaceId,
            @Valid @RequestBody CreateResearchTaskRequest request) {
        return ResponseEntity.ok(researchTaskService.createAndRun(workspaceId, request.getQuery()));
    }

    @GetMapping
    @Operation(summary = "任务列表（本工作空间）")
    public ResponseEntity<List<TaskSummaryResponse>> list(@PathVariable String workspaceId) {
        return ResponseEntity.ok(researchTaskService.list(workspaceId));
    }

    @GetMapping("/{taskId}")
    @Operation(summary = "任务详情")
    public ResponseEntity<TaskSummaryResponse> get(
            @PathVariable String workspaceId,
            @PathVariable String taskId) {
        return ResponseEntity.ok(researchTaskService.get(workspaceId, taskId));
    }
}
