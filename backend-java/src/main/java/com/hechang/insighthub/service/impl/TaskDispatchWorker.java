package com.hechang.insighthub.service.impl;

import jakarta.annotation.Resource;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import com.hechang.insighthub.mapper.TaskDispatchOutboxMapper;

@Component
public class TaskDispatchWorker {
    @Resource private TaskDispatchOutboxMapper mapper;
    @Resource private TransactionTemplate transactionTemplate;
    @Resource private TaskDispatchExecutor dispatchExecutor;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(TaskDispatchRequested event) { inTransaction(event.outboxId()); }

    @Scheduled(fixedDelayString = "${insighthub.task.dispatch-retry-ms:5000}")
    public void retryReady() { mapper.findReady(20).forEach(row -> inTransaction(row.getId())); }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAfterRestart() {
        mapper.recoverInFlight();
        retryReady();
    }

    private void inTransaction(String outboxId) {
        Boolean claimed = transactionTemplate.execute(ignored -> mapper.claim(outboxId) == 1);
        if (Boolean.TRUE.equals(claimed)) {
            // This call crosses a Spring proxy. The status is deliberately kept
            // DISPATCHING until the asynchronous executor has consumed it.
            try {
                dispatchExecutor.execute(outboxId);
            } catch (RuntimeException ex) {
                mapper.markRetry(outboxId, "dispatch executor rejected command", java.time.LocalDateTime.now().plusSeconds(5));
            }
        }
    }
}
