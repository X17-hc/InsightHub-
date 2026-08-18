package com.hechang.insighthub.service.impl;

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

    @InjectMocks
    private KnowledgeIngestEventListener listener;

    @Test
    void delegatesCommittedEventToKnowledgeService() {
        listener.handle(new DocumentIngestRequested("workspace-1", "kb-1", "doc-1"));

        verify(knowledgeService).ingestDocument("workspace-1", "kb-1", "doc-1");
    }
}
