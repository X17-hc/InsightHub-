package com.hechang.insighthub.service.impl;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hechang.insighthub.config.TaskProperties;
import com.hechang.insighthub.mapper.ResearchTaskMapper;
import com.hechang.insighthub.mapper.TaskDispatchOutboxMapper;
import com.hechang.insighthub.model.entity.TaskDispatchOutbox;
import com.hechang.insighthub.redis.TaskSlotTracker;
import com.hechang.insighthub.redis.WorkspaceConcurrencyService;
import com.hechang.insighthub.service.TaskDispatchCommand;
import com.hechang.insighthub.service.TaskExecutionService;

/**
 * Asynchronous half of durable task dispatch.
 *
 * <p>Keeping this proxy in a separate bean is intentional: a command remains
 * {@code DISPATCHING} until the stream consumer returns, so a process crash is
 * recoverable on the next application start.</p>
 */
@Component
public class TaskDispatchExecutor {

    @Resource private TaskDispatchOutboxMapper mapper;
    @Resource private ObjectMapper objectMapper;
    @Resource private TaskExecutionService taskExecutionService;
    @Resource private ResearchTaskMapper researchTaskMapper;
    @Resource private TaskSlotTracker slotTracker;
    @Resource private WorkspaceConcurrencyService concurrencyService;
    @Resource private TaskProperties taskProperties;

    @Async("taskExecutor")
    public void execute(String outboxId) {
        TaskDispatchOutbox row = mapper.selectOneById(outboxId);
        if (row == null || !"DISPATCHING".equals(row.getStatus())) {
            return;
        }
        try {
            TaskDispatchCommand command = objectMapper.readValue(row.getPayloadJson(), TaskDispatchCommand.class);
            taskExecutionService.executeDispatch(command);
            mapper.markDispatched(outboxId);
        } catch (Exception ex) {
            String error = abbreviate(ex.getMessage());
            if (row.getAttemptCount() >= taskProperties.getDispatchMaxAttempts()) {
                mapper.markFailed(outboxId, error);
                if (researchTaskMapper.failDispatchIfCurrentRun(row.getTaskId(), row.getWorkspaceId(), row.getRunId(),
                        "DISPATCH_EXHAUSTED", error) == 1) {
                    slotTracker.releaseOnce(row.getTaskId(), row.getWorkspaceId(),
                            permit -> concurrencyService.release(row.getWorkspaceId(), permit));
                }
            } else {
                mapper.markRetry(outboxId, error, LocalDateTime.now().plusSeconds(5));
            }
        }
    }

    private static String abbreviate(String value) {
        if (value == null) return "dispatch failed";
        return value.length() <= 1024 ? value : value.substring(0, 1024);
    }
}
