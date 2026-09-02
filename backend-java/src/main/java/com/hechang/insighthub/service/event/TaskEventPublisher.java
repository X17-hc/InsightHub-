package com.hechang.insighthub.service.event;

import com.hechang.insighthub.service.realtime.TaskEventSseHub;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.hechang.insighthub.redis.TaskControlRedis;
import lombok.RequiredArgsConstructor;

/** Publishes persisted server events only after their surrounding business transaction commits. */
@Component
@RequiredArgsConstructor
public class TaskEventPublisher {

    private final TaskControlRedis taskControlRedis;
    private final TaskEventSseHub sseHub;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(TaskEventPublished event) {
        if (event.event() == null) {
            return;
        }
        taskControlRedis.publishEvent(event.taskId(), event.event().json());
        sseHub.broadcastLocal(event.taskId(), event.event().eventNo(), "PLAN_REVISED", event.event().json());
    }
}
