package com.hechang.insighthub.service.task;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.hechang.insighthub.exception.BusinessException;
import com.hechang.insighthub.model.enums.TaskStatus;

/**
 * 任务状态迁移校验。
 */
@Component
public class TaskStateMachine {

    private final Map<TaskStatus, Set<TaskStatus>> transitions = new EnumMap<>(TaskStatus.class);

    public TaskStateMachine() {
        transitions.put(TaskStatus.CREATED, EnumSet.of(TaskStatus.PLANNING, TaskStatus.CANCELLED));
        transitions.put(TaskStatus.PLANNING, EnumSet.of(
                TaskStatus.WAITING_APPROVAL, TaskStatus.RUNNING, TaskStatus.FAILED, TaskStatus.CANCELLED));
        // 待确认计划时前端会显示「取消」；用户应能在执行开始前终止任务
        transitions.put(TaskStatus.WAITING_APPROVAL, EnumSet.of(
                TaskStatus.PLANNING, TaskStatus.RUNNING, TaskStatus.CANCELLED));
        transitions.put(TaskStatus.RUNNING, EnumSet.of(
                TaskStatus.PAUSING,
                TaskStatus.PAUSED,
                TaskStatus.REVIEWING,
                TaskStatus.GENERATING,
                TaskStatus.FAILED,
                TaskStatus.CANCELLED));
        transitions.put(TaskStatus.PAUSING, EnumSet.of(
                TaskStatus.PAUSED,
                TaskStatus.GENERATING,
                TaskStatus.FAILED,
                TaskStatus.CANCELLED));
        transitions.put(TaskStatus.PAUSED, EnumSet.of(TaskStatus.RUNNING, TaskStatus.CANCELLED));
        transitions.put(TaskStatus.REVIEWING, EnumSet.of(
                TaskStatus.RUNNING, TaskStatus.GENERATING, TaskStatus.CANCELLED));
        transitions.put(TaskStatus.GENERATING, EnumSet.of(
                TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED));
        transitions.put(TaskStatus.FAILED, EnumSet.of(TaskStatus.RUNNING));
        transitions.put(TaskStatus.COMPLETED, EnumSet.of(TaskStatus.RUNNING));
        transitions.put(TaskStatus.CANCELLED, EnumSet.noneOf(TaskStatus.class));
    }

    /**
     * 校验并返回目标状态；非法迁移抛 409。
     */
    public TaskStatus transition(TaskStatus from, TaskStatus to) {
        if (!canTransition(from, to)) {
            throw BusinessException.conflict(
                    "INVALID_STATUS_TRANSITION",
                    "cannot transition from " + from + " to " + to);
        }
        return to;
    }

    public boolean canTransition(TaskStatus from, TaskStatus to) {
        if (from == null || to == null) return false;
        Set<TaskStatus> allowed = transitions.getOrDefault(from, EnumSet.noneOf(TaskStatus.class));
        return allowed.contains(to);
    }
}
