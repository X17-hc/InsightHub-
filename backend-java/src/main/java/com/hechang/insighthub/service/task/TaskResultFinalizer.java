package com.hechang.insighthub.service.task;

import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.hechang.insighthub.mapper.ResearchTaskMapper;
import com.hechang.insighthub.model.entity.ResearchTask;
import com.hechang.insighthub.model.enums.TaskStatus;
import com.hechang.insighthub.service.TaskResultService;

import lombok.RequiredArgsConstructor;

/** Owns the short transaction that turns an Agent terminal result into local state. */
@Component
@RequiredArgsConstructor
public class TaskResultFinalizer {

    private static final Logger log = LoggerFactory.getLogger(TaskResultFinalizer.class);

    private final ResearchTaskMapper researchTaskMapper;
    private final TaskResultService taskResultService;
    private final TaskStateMachine stateMachine;
    private final TaskEventService taskEventService;
    private final TransactionTemplate transactionTemplate;

    public TaskEventService.StoredEvent finalizeResult(String taskId, String workspaceId, JsonNode result) {
        AtomicReference<TaskEventService.StoredEvent> stored = new AtomicReference<>();
        transactionTemplate.executeWithoutResult(ignored -> {
            if (applyResult(taskId, workspaceId, result)) {
                stored.set(taskEventService.insertAgentResult(taskId, result));
            }
        });
        return stored.get();
    }

    public void markFailed(String taskId, String workspaceId, String runId, String code, String message) {
        forceTerminal(taskId, workspaceId, TaskStatus.FAILED, runId, code, message);
    }

    public void forceTerminal(
            String taskId,
            String workspaceId,
            TaskStatus target,
            String runId,
            String errorCode,
            String errorMessage) {
        transactionTemplate.executeWithoutResult(ignored ->
                forceTerminalInTransaction(taskId, workspaceId, target, runId, errorCode, errorMessage));
    }

    private boolean applyResult(String taskId, String workspaceId, JsonNode result) {
        String status = text(result, "status");
        String runId = text(result, "runId");
        String report = text(result, "reportMarkdown");
        JsonNode error = result.get("error");
        String errorCode = error == null || error.isNull() ? null : text(error, "code");
        String errorMessage = error == null || error.isNull() ? null : text(error, "message");

        ResearchTask task = researchTaskMapper.findByIdAndWorkspaceForUpdate(taskId, workspaceId);
        if (task == null || TaskStatus.isTerminal(task.getStatus())) {
            return false;
        }
        if (!isCurrentRun(task, runId)) {
            // 重试会替换 currentRunId；迟到的旧流终态必须整条忽略，不能保存报告或推进状态。
            log.info("ignore terminal result from stale run taskId={} currentRunId={} eventRunId={}",
                    taskId, task.getCurrentRunId(), runId);
            return false;
        }
        TaskStatus current = TaskStatus.valueOf(task.getStatus());
        if (TaskStatus.PAUSED.matches(status)) {
            if (current == TaskStatus.RUNNING || current == TaskStatus.PAUSING) {
                stateMachine.transition(current, TaskStatus.PAUSED);
                researchTaskMapper.updateStatus(taskId, workspaceId, TaskStatus.PAUSED.name(), null, null);
            }
            return true;
        }
        if (TaskStatus.WAITING_APPROVAL.matches(status)) {
            if (current != TaskStatus.WAITING_APPROVAL) {
                researchTaskMapper.updateStatusIfCurrent(
                        taskId, workspaceId, current.name(), TaskStatus.WAITING_APPROVAL.name(), 30, "wait_for_approval");
            }
            return true;
        }
        if (TaskStatus.COMPLETED.matches(status)) {
            if (current == TaskStatus.PAUSED) {
                log.info("ignore COMPLETED while PAUSED taskId={}", taskId);
                return false;
            }
            if (current == TaskStatus.RUNNING || current == TaskStatus.PAUSING) {
                stateMachine.transition(current, TaskStatus.GENERATING);
                researchTaskMapper.updateStatus(taskId, workspaceId, TaskStatus.GENERATING.name(), 80, "write_report");
                current = TaskStatus.GENERATING;
            }
            if (current == TaskStatus.GENERATING) {
                if (report == null || report.isBlank()) {
                    throw new IllegalStateException("completed task has no report");
                }
                taskResultService.saveReportAndCitations(
                        taskId, workspaceId, report, result.get("citations"), result.get("quality"));
                stateMachine.transition(TaskStatus.GENERATING, TaskStatus.COMPLETED);
                researchTaskMapper.updateTaskFinished(taskId, workspaceId, TaskStatus.COMPLETED.name(), runId, null, null);
            }
            return true;
        }
        if (TaskStatus.CANCELLED.matches(status)) {
            forceTerminalInTransaction(taskId, workspaceId, TaskStatus.CANCELLED, runId, errorCode, errorMessage);
            return true;
        }
        forceTerminalInTransaction(taskId, workspaceId, TaskStatus.FAILED, runId, errorCode, errorMessage);
        return true;
    }

    private void forceTerminalInTransaction(
            String taskId,
            String workspaceId,
            TaskStatus target,
            String runId,
            String errorCode,
            String errorMessage) {
        ResearchTask task = researchTaskMapper.findByIdAndWorkspaceForUpdate(taskId, workspaceId);
        if (task == null || TaskStatus.isTerminal(task.getStatus())) {
            return;
        }
        if (!isCurrentRun(task, runId)) {
            log.info("ignore forced terminal state from stale run taskId={} currentRunId={} eventRunId={}",
                    taskId, task.getCurrentRunId(), runId);
            return;
        }
        TaskStatus current = TaskStatus.tryParse(task.getStatus());
        if (current == null) {
            log.error("force terminal status from invalid state taskId={} current={} target={}",
                    taskId, task.getStatus(), target);
        } else if (current != target && stateMachine.canTransition(current, target)) {
            stateMachine.transition(current, target);
        } else if (current != target) {
            log.warn("force terminal status bypassing state machine taskId={} from={} to={}", taskId, current, target);
        }
        researchTaskMapper.updateTaskFinished(
                taskId, workspaceId, target.name(), runId, errorCode, truncate(errorMessage, 1024));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static boolean isCurrentRun(ResearchTask task, String runId) {
        return runId != null && !runId.isBlank() && runId.equals(task.getCurrentRunId());
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
