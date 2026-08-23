package com.hechang.insighthub.service.impl;

/** Published from a committed knowledge-base disable transaction. */
public record KnowledgeChunksDeleteRequested(String workspaceId, String knowledgeBaseId) {
}
