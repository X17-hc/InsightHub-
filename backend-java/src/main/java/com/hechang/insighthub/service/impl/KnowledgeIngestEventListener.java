package com.hechang.insighthub.service.impl;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.hechang.insighthub.service.KnowledgeService;

import lombok.RequiredArgsConstructor;

/** 将文档入库延后至元数据事务提交后，避免 Python 服务读取未提交记录。 */
@Component
@RequiredArgsConstructor
public class KnowledgeIngestEventListener {

    private final KnowledgeService knowledgeService;
    private final KnowledgeChunkCleanupExecutor cleanupExecutor;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handle(DocumentIngestRequested event) {
        knowledgeService.ingestDocument(
                event.workspaceId(), event.knowledgeBaseId(), event.documentId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handle(KnowledgeChunksDeleteRequested event) {
        cleanupExecutor.deleteByKnowledgeBase(event.workspaceId(), event.knowledgeBaseId());
    }
}
