package com.hechang.insighthub.service.impl;

import com.hechang.insighthub.service.event.DocumentIngestRequested;
import com.hechang.insighthub.service.event.KnowledgeChunksDeleteRequested;
import com.hechang.insighthub.service.event.KnowledgeIngestEventListener;
import com.hechang.insighthub.service.execution.KnowledgeChunkCleanupExecutor;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.hechang.insighthub.service.KnowledgeService;

@ExtendWith(MockitoExtension.class)
class KnowledgeIngestEventListenerTest {

    @Mock
    private KnowledgeService knowledgeService;
    @Mock
    private KnowledgeChunkCleanupExecutor cleanupExecutor;

    @InjectMocks
    private KnowledgeIngestEventListener listener;

    @Test
    void delegatesCommittedEventToKnowledgeService() {
        listener.handle(new DocumentIngestRequested("workspace-1", "kb-1", "doc-1"));

        verify(knowledgeService).ingestDocument("workspace-1", "kb-1", "doc-1");
    }

    @Test
    void delegatesCommittedCleanupToTheDedicatedExecutor() {
        listener.handle(new KnowledgeChunksDeleteRequested("workspace-1", "kb-1"));

        verify(cleanupExecutor).deleteByKnowledgeBase("workspace-1", "kb-1");
    }
}
