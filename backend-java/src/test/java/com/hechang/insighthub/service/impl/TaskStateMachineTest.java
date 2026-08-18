package com.hechang.insighthub.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.hechang.insighthub.exception.BusinessException;
import com.hechang.insighthub.model.enums.TaskStatus;

class TaskStateMachineTest {

    private final TaskStateMachine stateMachine = new TaskStateMachine();

    @Test
    void pausingCanBeAcknowledgedOrFinishNaturally() {
        assertDoesNotThrow(() -> stateMachine.transition(TaskStatus.PAUSING, TaskStatus.PAUSED));
        assertDoesNotThrow(() -> stateMachine.transition(TaskStatus.PAUSING, TaskStatus.GENERATING));
    }

    @Test
    void pausedTaskCannotSkipResumeAndGenerate() {
        assertThrows(
                BusinessException.class,
                () -> stateMachine.transition(TaskStatus.PAUSED, TaskStatus.GENERATING));
    }

    @Test
    void planningTaskCanWaitForPlanApproval() {
        assertDoesNotThrow(() -> stateMachine.transition(TaskStatus.PLANNING, TaskStatus.WAITING_APPROVAL));
    }
}
