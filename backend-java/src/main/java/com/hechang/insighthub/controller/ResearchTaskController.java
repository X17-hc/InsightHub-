package com.hechang.insighthub.controller;

import java.util.List;

import com.hechang.insighthub.model.dto.task.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.hechang.insighthub.common.BaseResponse;
import com.hechang.insighthub.common.ResultUtils;
import com.hechang.insighthub.model.dto.knowledge.CitationResponse;
import com.hechang.insighthub.service.ResearchTaskService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 工作空间内研究任务 API（JWT；支持异步 SSE）。
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/research/tasks")
@Tag(name = "ResearchTask")
@SecurityRequirement(name = "BearerAuth")
@RequiredArgsConstructor
public class ResearchTaskController {

    private final ResearchTaskService researchTaskService;


    @PostMapping
    @Operation(summary = "异步创建研究任务（202）")
    public ResponseEntity<BaseResponse<CreateTaskAcceptedResponse>> create(
            @PathVariable String workspaceId,
            @Valid @RequestBody CreateResearchTaskRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ResultUtils.success(researchTaskService.createAsync(workspaceId, request)));
    }

    @PostMapping("/sync")
    @Operation(summary = "同步创建并执行（兼容第 1/2 周）")
    public BaseResponse<AgentTaskResponseDto> createSync(
            @PathVariable String workspaceId,
            @Valid @RequestBody CreateResearchTaskRequest request) {
        return ResultUtils.success(researchTaskService.createAndRun(workspaceId, request));
    }

    @GetMapping
    @Operation(summary = "任务列表（本工作空间）")
    public BaseResponse<List<TaskSummaryResponse>> list(@PathVariable String workspaceId) {
        return ResultUtils.success(researchTaskService.list(workspaceId));
    }

    @GetMapping("/{taskId}")
    @Operation(summary = "任务详情")
    public BaseResponse<TaskSummaryResponse> get(
            @PathVariable String workspaceId,
            @PathVariable String taskId) {
        return ResultUtils.success(researchTaskService.get(workspaceId, taskId));
    }

    @GetMapping("/{taskId}/report")
    @Operation(summary = "Get the latest generated report")
    public BaseResponse<ReportResponse> report(
            @PathVariable String workspaceId,
            @PathVariable String taskId) {
        return ResultUtils.success(researchTaskService.getReport(workspaceId, taskId));
    }

    @GetMapping("/{taskId}/citations")
    @Operation(summary = "任务引用列表（结论可追溯来源）")
    public BaseResponse<List<CitationResponse>> citations(
            @PathVariable String workspaceId,
            @PathVariable String taskId) {
        return ResultUtils.success(researchTaskService.listCitations(workspaceId, taskId));
    }

    /**
     * 历史事件 JSON（与 SSE 路径分离，避免 content-negotiation 冲突）。
     */
    @GetMapping({"/{taskId}/event-records", "/{taskId}/event-log"})
    @Operation(summary = "任务历史事件列表（详情页首屏灌入）")
    public BaseResponse<List<TaskEventResponse>> eventLog(
            @PathVariable String workspaceId,
            @PathVariable String taskId,
            @RequestParam(value = "fromEventNo", required = false, defaultValue = "0") long fromEventNo) {
        return ResultUtils.success(researchTaskService.listEvents(workspaceId, taskId, fromEventNo));
    }

    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "任务事件 SSE（支持 Last-Event-ID / fromEventNo 续传）")
    public SseEmitter events(
            @PathVariable String workspaceId,
            @PathVariable String taskId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(value = "fromEventNo", required = false) Long fromEventNo) {
        long from = 0L;
        if (fromEventNo != null) {
            from = fromEventNo;
        } else if (lastEventId != null && !lastEventId.isBlank()) {
            try {
                from = Long.parseLong(lastEventId.trim());
            } catch (NumberFormatException ignored) {
                from = 0L;
            }
        }
        return researchTaskService.streamEvents(workspaceId, taskId, from);
    }

    @PostMapping("/{taskId}/pause")
    @Operation(summary = "暂停任务")
    public BaseResponse<TaskControlResponse> pause(
            @PathVariable String workspaceId,
            @PathVariable String taskId) {
        return ResultUtils.success(researchTaskService.pause(workspaceId, taskId));
    }

    @PostMapping("/{taskId}/resume")
    @Operation(summary = "恢复任务")
    public BaseResponse<TaskControlResponse> resume(
            @PathVariable String workspaceId,
            @PathVariable String taskId) {
        return ResultUtils.success(researchTaskService.resume(workspaceId, taskId));
    }

    @PostMapping("/{taskId}/cancel")
    @Operation(summary = "取消任务")
    public BaseResponse<TaskControlResponse> cancel(
            @PathVariable String workspaceId,
            @PathVariable String taskId) {
        return ResultUtils.success(researchTaskService.cancel(workspaceId, taskId));
    }

    @PostMapping("/{taskId}/retry")
    @Operation(summary = "失败重试")
    public ResponseEntity<BaseResponse<CreateTaskAcceptedResponse>> retry(
            @PathVariable String workspaceId,
            @PathVariable String taskId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ResultUtils.success(researchTaskService.retry(workspaceId, taskId)));
    }

    @GetMapping("/{taskId}/plan")
    @Operation(summary = "查询当前计划修订版")
    public BaseResponse<PlanRevisionResponse> currentPlan(
            @PathVariable String workspaceId, @PathVariable String taskId) {
        return ResultUtils.success(researchTaskService.getCurrentPlan(workspaceId, taskId));
    }

    @GetMapping("/{taskId}/plans")
    @Operation(summary = "查询计划历史")
    public BaseResponse<List<PlanRevisionResponse>> planHistory(
            @PathVariable String workspaceId, @PathVariable String taskId) {
        return ResultUtils.success(researchTaskService.listPlanHistory(workspaceId, taskId));
    }

    @PostMapping("/{taskId}/plan/approve")
    @Operation(summary = "确认当前计划并继续执行")
    public BaseResponse<PlanActionResponse> approvePlan(
            @PathVariable String workspaceId, @PathVariable String taskId,
            @Valid @RequestBody ApprovePlanRequest request, HttpServletRequest httpRequest) {
        return ResultUtils.success(researchTaskService.approvePlan(
                workspaceId, taskId, request, httpRequest.getRemoteAddr()));
    }

    @PostMapping("/{taskId}/plan/revise")
    @Operation(summary = "提交文字修订并生成新计划")
    public BaseResponse<PlanActionResponse> revisePlan(
            @PathVariable String workspaceId, @PathVariable String taskId,
            @Valid @RequestBody RevisePlanRequest request, HttpServletRequest httpRequest) {
        return ResultUtils.success(researchTaskService.revisePlan(
                workspaceId, taskId, request, httpRequest.getRemoteAddr()));
    }


}
