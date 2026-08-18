package com.hechang.insighthub.service.impl;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.hechang.insighthub.redis.TaskControlRedis;

/** Publishes persisted server events only after their surrounding business transaction commits. */
@Component
public class TaskEventPublisher {

    @Resource private TaskControlRedis taskControlRedis;
    @Resource private TaskEventSseHub sseHub;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(TaskEventPublished event) {
        if (event.event() == null) {
            return;
        }
        taskControlRedis.publishEvent(event.taskId(), event.event().json());
        sseHub.broadcastLocal(event.taskId(), event.event().eventNo(), "PLAN_REVISED", event.event().json());
    }
}
