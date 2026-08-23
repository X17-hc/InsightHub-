package com.hechang.insighthub.service.impl;

import org.springframework.stereotype.Component;

import com.hechang.insighthub.mapper.ResearchTaskMapper;
import com.hechang.insighthub.model.dto.task.AgentEventDto;
import com.hechang.insighthub.model.entity.ResearchTask;
import com.hechang.insighthub.model.enums.TaskStatus;
import com.hechang.insighthub.service.PlanApplicationService;

import lombok.RequiredArgsConstructor;

/** Applies state changes caused by non-terminal Agent events. */
@Component
@RequiredArgsConstructor
public class TaskEventSideEffectHandler {

    private final ResearchTaskMapper researchTaskMapper;
    private final TaskStateMachine stateMachine;
    private final PlanApplicationService planApplicationService;
    private final TaskResultFinalizer resultFinalizer;

    public void apply(String taskId, String workspaceId, AgentEventDto event) {
        ResearchTask task = researchTaskMapper.findByIdAndWorkspace(taskId, workspaceId);
        if (task == null || TaskStatus.isTerminal(task.getStatus())) {
            return;
        }
        TaskStatus current = TaskStatus.tryParse(task.getStatus());
        if (current == null) {
            return;
        }
        boolean paused = current == TaskStatus.PAUSED || current == TaskStatus.PAUSING;
        switch (event.getType()) {
            case "PLAN_CREATED" -> recordPlan(taskId, workspaceId, task, event, paused);
            case "APPROVAL_REQUIRED" -> transitionToWaiting(taskId, workspaceId, current);
            case "NODE_STARTED", "NODE_COMPLETED" -> updateRunningNode(taskId, workspaceId, current, event.getNode());
            case "CRITIC_STARTED" -> updateRunningProgress(taskId, workspaceId, current, 55, "critic_review");
            case "CRITIQUE_COMPLETED" -> updateRunningProgress(taskId, workspaceId, current, 60, "critic_review");
            case "SUPPLEMENT_RESEARCH_REQUESTED" -> updateRunningProgress(taskId, workspaceId, current, 65, "supplement_research");
            case "TASK_PAUSED" -> markPaused(taskId, workspaceId, current, event.getNode());
            case "TASK_COMPLETED" -> markGenerating(taskId, workspaceId, current);
            case "TASK_FAILED" -> markCancelledWhenRequested(taskId, workspaceId, event);
            default -> {
                // Informational event.
            }
        }
    }

    private void recordPlan(
            String taskId, String workspaceId, ResearchTask task, AgentEventDto event, boolean paused) {
        if (!paused && (TaskStatus.PLANNING.matches(task.getStatus()) || TaskStatus.RUNNING.matches(task.getStatus()))) {
            planApplicationService.recordPlannerResult(
                    taskId, workspaceId, task.getCreatorId(), event.getRunId(), event.getData());
        }
    }

    private void transitionToWaiting(String taskId, String workspaceId, TaskStatus current) {
        if (current == TaskStatus.PLANNING || current == TaskStatus.RUNNING) {
            researchTaskMapper.updateStatusIfCurrent(
                    taskId, workspaceId, current.name(), TaskStatus.WAITING_APPROVAL.name(), 30, "wait_for_approval");
        }
    }

    private void updateRunningNode(String taskId, String workspaceId, TaskStatus current, String node) {
        if (current == TaskStatus.RUNNING) {
            researchTaskMapper.updateStatusIfCurrent(
                    taskId, workspaceId, TaskStatus.RUNNING.name(), TaskStatus.RUNNING.name(), null, node);
        }
    }

    private void updateRunningProgress(String taskId, String workspaceId, TaskStatus current, int progress, String node) {
        if (current == TaskStatus.RUNNING) {
            researchTaskMapper.updateStatusIfCurrent(
                    taskId, workspaceId, TaskStatus.RUNNING.name(), TaskStatus.RUNNING.name(), progress, node);
        }
    }

    private void markPaused(String taskId, String workspaceId, TaskStatus current, String node) {
        if (current == TaskStatus.RUNNING || current == TaskStatus.PAUSING) {
            stateMachine.transition(current, TaskStatus.PAUSED);
            researchTaskMapper.updateStatusIfCurrent(taskId, workspaceId, current.name(), TaskStatus.PAUSED.name(), null, node);
        }
    }

    private void markGenerating(String taskId, String workspaceId, TaskStatus current) {
        if (current == TaskStatus.RUNNING || current == TaskStatus.PAUSING) {
            stateMachine.transition(current, TaskStatus.GENERATING);
            researchTaskMapper.updateStatusIfCurrent(
                    taskId, workspaceId, current.name(), TaskStatus.GENERATING.name(), 80, "write_report");
        }
    }

    private void markCancelledWhenRequested(String taskId, String workspaceId, AgentEventDto event) {
        if (event.getData() == null || !TaskStatus.CANCELLED.matches(String.valueOf(event.getData().get("code")))) {
            return;
        }
        resultFinalizer.forceTerminal(
                taskId,
                workspaceId,
                TaskStatus.CANCELLED,
                event.getRunId(),
                String.valueOf(event.getData().get("code")),
                event.getData().get("message") == null ? null : String.valueOf(event.getData().get("message")));
    }
}
