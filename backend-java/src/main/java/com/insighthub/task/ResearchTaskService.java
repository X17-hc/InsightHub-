package com.insighthub.task;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.insighthub.common.BusinessException;
import com.insighthub.integration.AgentServiceClient;
import com.insighthub.security.SecurityUtils;
import com.insighthub.task.dto.TaskSummaryResponse;
import com.insighthub.web.dto.AgentTaskResponseDto;
import com.insighthub.workspace.WorkspaceAccessService;

/**
 * 研究任务：工作空间隔离 + 状态机 + 调用 Python Agent。
 */
@Service
public class ResearchTaskService {

    private static final Logger log = LoggerFactory.getLogger(ResearchTaskService.class);

    private final AgentServiceClient agentServiceClient;
    private final TaskRepository taskRepository;
    private final WorkspaceAccessService accessService;
    private final TaskStateMachine stateMachine;
    private final TransactionTemplate transactionTemplate;

    public ResearchTaskService(
            AgentServiceClient agentServiceClient,
            TaskRepository taskRepository,
            WorkspaceAccessService accessService,
            TaskStateMachine stateMachine,
            TransactionTemplate transactionTemplate) {
        this.agentServiceClient = agentServiceClient;
        this.taskRepository = taskRepository;
        this.accessService = accessService;
        this.stateMachine = stateMachine;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 在指定工作空间创建并同步执行研究任务。
     * 注意：Agent 远程调用不包长事务，终态落库用短事务保证一致性。
     */
    public AgentTaskResponseDto createAndRun(String workspaceId, String query) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);

        String taskId = "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String traceId = "trace-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        taskRepository.insertCreatedTask(taskId, workspaceId, userId, query, traceId);
        advance(taskId, workspaceId, TaskStatus.CREATED, TaskStatus.PLANNING, 10, "create_plan");
        advance(taskId, workspaceId, TaskStatus.PLANNING, TaskStatus.RUNNING, 30, "dispatch_tasks");
        log.info("Created research task {} workspace={} traceId={}", taskId, workspaceId, traceId);

        AgentTaskResponseDto response;
        try {
            response = agentServiceClient.createTask(taskId, workspaceId, userId, query, traceId);
        } catch (Exception ex) {
            log.error("Agent call failed taskId={} workspace={}", taskId, workspaceId, ex);
            markFailed(taskId, workspaceId, null, "AGENT_CALL_FAILED", "agent service call failed");
            throw new BusinessException("AGENT_CALL_FAILED", "agent service call failed", HttpStatus.BAD_GATEWAY);
        }
        // 空响应也必须落终态，避免任务永久停留在 RUNNING
        if (response == null) {
            log.error("Agent returned empty body taskId={} workspace={}", taskId, workspaceId);
            markFailed(taskId, workspaceId, null, "AGENT_EMPTY_RESPONSE", "agent service returned empty body");
            throw new BusinessException("AGENT_EMPTY_RESPONSE", "agent service returned empty body", HttpStatus.BAD_GATEWAY);
        }
        response.setTraceId(traceId);

        String status = response.getStatus() == null ? "FAILED" : response.getStatus();
        String errorCode = null;
        String errorMessage = null;
        if (response.getError() != null) {
            errorCode = String.valueOf(response.getError().getOrDefault("code", "AGENT_ERROR"));
            errorMessage = String.valueOf(response.getError().getOrDefault("message", ""));
        }

        final String finalStatus = status;
        final String finalErrorCode = errorCode;
        final String finalErrorMessage = errorMessage;
        transactionTemplate.executeWithoutResult(tx -> {
            if ("COMPLETED".equalsIgnoreCase(finalStatus)) {
                advance(taskId, workspaceId, TaskStatus.RUNNING, TaskStatus.GENERATING, 80, "write_report");
                advance(taskId, workspaceId, TaskStatus.GENERATING, TaskStatus.COMPLETED, 100, "finalize");
                taskRepository.updateTaskFinished(
                        taskId, workspaceId, TaskStatus.COMPLETED.name(), response.getRunId(), null, null);
                if (response.getReportMarkdown() != null) {
                    String reportId = "report-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                    taskRepository.insertReport(
                            reportId,
                            taskId,
                            workspaceId,
                            response.getReportMarkdown(),
                            extractTitle(response.getReportMarkdown()));
                }
            } else {
                advance(taskId, workspaceId, TaskStatus.RUNNING, TaskStatus.FAILED, 30, null);
                taskRepository.updateTaskFinished(
                        taskId,
                        workspaceId,
                        TaskStatus.FAILED.name(),
                        response.getRunId(),
                        finalErrorCode,
                        finalErrorMessage);
            }
            taskRepository.insertEvents(taskId, response.getEvents());
        });

        response.setTaskId(taskId);
        response.setStatus(status.toUpperCase());
        return response;
    }

    public List<TaskSummaryResponse> list(String workspaceId) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);
        return taskRepository.listByWorkspace(workspaceId).stream().map(this::toSummary).toList();
    }

    public TaskSummaryResponse get(String workspaceId, String taskId) {
        String userId = SecurityUtils.requireUserId();
        accessService.requireMember(workspaceId, userId);
        return taskRepository.findByIdAndWorkspace(taskId, workspaceId)
                .map(this::toSummary)
                .orElseThrow(() -> BusinessException.notFound("task not found"));
    }

    /** 将任务标记为 FAILED（短事务）。 */
    private void markFailed(
            String taskId,
            String workspaceId,
            String runId,
            String errorCode,
            String errorMessage) {
        transactionTemplate.executeWithoutResult(tx -> {
            advance(taskId, workspaceId, TaskStatus.RUNNING, TaskStatus.FAILED, 30, null);
            taskRepository.updateTaskFinished(
                    taskId, workspaceId, TaskStatus.FAILED.name(), runId, errorCode, errorMessage);
        });
    }

    private void advance(
            String taskId,
            String workspaceId,
            TaskStatus from,
            TaskStatus to,
            int progress,
            String node) {
        stateMachine.transition(from, to);
        taskRepository.updateStatus(taskId, workspaceId, to.name(), progress, node);
    }

    private TaskSummaryResponse toSummary(TaskRepository.TaskRow row) {
        TaskSummaryResponse r = new TaskSummaryResponse();
        r.setTaskId(row.id());
        r.setWorkspaceId(row.workspaceId());
        r.setCreatorId(row.creatorId());
        r.setQuery(row.query());
        r.setStatus(row.status());
        r.setProgress(row.progress());
        r.setTraceId(row.traceId());
        r.setRunId(row.currentRunId());
        r.setErrorCode(row.errorCode());
        r.setErrorMessage(row.errorMessage());
        r.setCreatedAt(row.createdAt());
        return r;
    }

    private static String extractTitle(String markdown) {
        for (String line : markdown.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                return trimmed.replaceFirst("^#+\\s*", "");
            }
        }
        return "InsightHub Report";
    }
}
