package com.insighthub.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.insighthub.task.ResearchTaskService;
import com.insighthub.task.dto.CreateTaskAcceptedResponse;
import com.insighthub.task.dto.TaskControlResponse;
import com.insighthub.task.dto.TaskSummaryResponse;
import com.insighthub.web.dto.AgentTaskResponseDto;
import com.insighthub.web.dto.CreateResearchTaskRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * 工作空间内研究任务 API（JWT；支持异步 SSE）。
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
    @Operation(summary = "异步创建研究任务（202）")
    public ResponseEntity<CreateTaskAcceptedResponse> create(
            @PathVariable String workspaceId,
            @Valid @RequestBody CreateResearchTaskRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(researchTaskService.createAsync(workspaceId, request.getQuery()));
    }

    @PostMapping("/sync")
    @Operation(summary = "同步创建并执行（兼容第 1/2 周）")
    public ResponseEntity<AgentTaskResponseDto> createSync(
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
    public ResponseEntity<TaskControlResponse> pause(
            @PathVariable String workspaceId,
            @PathVariable String taskId) {
        return ResponseEntity.ok(researchTaskService.pause(workspaceId, taskId));
    }

    @PostMapping("/{taskId}/resume")
    @Operation(summary = "恢复任务")
    public ResponseEntity<TaskControlResponse> resume(
            @PathVariable String workspaceId,
            @PathVariable String taskId) {
        return ResponseEntity.ok(researchTaskService.resume(workspaceId, taskId));
    }

    @PostMapping("/{taskId}/cancel")
    @Operation(summary = "取消任务")
    public ResponseEntity<TaskControlResponse> cancel(
            @PathVariable String workspaceId,
            @PathVariable String taskId) {
        return ResponseEntity.ok(researchTaskService.cancel(workspaceId, taskId));
    }

    @PostMapping("/{taskId}/retry")
    @Operation(summary = "失败重试")
    public ResponseEntity<CreateTaskAcceptedResponse> retry(
            @PathVariable String workspaceId,
            @PathVariable String taskId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(researchTaskService.retry(workspaceId, taskId));
    }
}
