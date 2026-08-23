package com.hechang.insighthub.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.hechang.insighthub.integration.KnowledgeIngestClient;

import lombok.RequiredArgsConstructor;

/** Executes remote vector cleanup after the local knowledge-base state is committed. */
@Component
@RequiredArgsConstructor
public class KnowledgeChunkCleanupExecutor {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeChunkCleanupExecutor.class);

    private final KnowledgeIngestClient ingestClient;

    @Async("knowledgeIngestExecutor")
    public void deleteByKnowledgeBase(String workspaceId, String knowledgeBaseId) {
        try {
            ingestClient.deleteChunksByKb(workspaceId, knowledgeBaseId);
        } catch (Exception ex) {
            log.warn("delete-by-kb failed workspaceId={} kbId={}", workspaceId, knowledgeBaseId, ex);
        }
    }
}
