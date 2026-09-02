package com.hechang.insighthub.service.impl;

import com.hechang.insighthub.service.realtime.TaskEventSseHub;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.hechang.insighthub.config.TaskProperties;
import com.hechang.insighthub.exception.BusinessException;
import com.hechang.insighthub.exception.ErrorCode;
import com.hechang.insighthub.mapper.ResearchTaskMapper;
import com.hechang.insighthub.mapper.TaskEventMapper;
import com.hechang.insighthub.model.entity.ResearchTask;
import com.hechang.insighthub.model.enums.TaskStatus;
import com.hechang.insighthub.service.task.TaskEventService;

class TaskEventSseHubTest {

    @Test
    void onlyCanonicalTaskResultClosesLiveStream() {
        assertFalse(TaskEventSseHub.isTerminalEnvelope("TASK_FAILED", null));
        assertFalse(TaskEventSseHub.isTerminalEnvelope("TASK_RESULT", null));
        assertFalse(TaskEventSseHub.isTerminalEnvelope("TASK_RESULT", "RUNNING"));
        assertTrue(TaskEventSseHub.isTerminalEnvelope("TASK_RESULT", "FAILED"));
        assertTrue(TaskEventSseHub.isTerminalEnvelope("TASK_RESULT", "COMPLETED"));
    }

    @Test
    void secondConnectionForSameTaskIsRejectedAtConfiguredLimit() {
        ResearchTaskMapper taskMapper = mock(ResearchTaskMapper.class);
        TaskEventMapper eventMapper = mock(TaskEventMapper.class);
        ResearchTask task = new ResearchTask();
        task.setStatus(TaskStatus.RUNNING.name());
        when(taskMapper.findByIdAndWorkspace("task-1", "workspace-1")).thenReturn(task);
        when(eventMapper.listAfterEventNo(any(), anyLong())).thenReturn(List.of());

        TaskProperties properties = new TaskProperties();
        properties.setSseMaxConnectionsPerTask(1);
        properties.setSseMaxConnectionsTotal(2);
        TaskEventSseHub hub = new TaskEventSseHub(
                taskMapper,
                eventMapper,
                mock(RedisMessageListenerContainer.class),
                new ObjectMapper(),
                mock(TaskEventService.class),
                properties,
                mock(ScheduledExecutorService.class));

        hub.subscribe("task-1", "workspace-1", 0L);
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> hub.subscribe("task-1", "workspace-1", 0L));

        assertEquals(ErrorCode.TOO_MANY_REQUEST.getCode(), exception.getCode());
    }
}
