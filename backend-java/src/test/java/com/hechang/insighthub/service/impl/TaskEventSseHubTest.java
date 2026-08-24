package com.hechang.insighthub.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TaskEventSseHubTest {

    @Test
    void onlyCanonicalTaskResultClosesLiveStream() {
        assertFalse(TaskEventSseHub.isTerminalEnvelope("TASK_FAILED", null));
        assertFalse(TaskEventSseHub.isTerminalEnvelope("TASK_RESULT", null));
        assertFalse(TaskEventSseHub.isTerminalEnvelope("TASK_RESULT", "RUNNING"));
        assertTrue(TaskEventSseHub.isTerminalEnvelope("TASK_RESULT", "FAILED"));
        assertTrue(TaskEventSseHub.isTerminalEnvelope("TASK_RESULT", "COMPLETED"));
    }
}
