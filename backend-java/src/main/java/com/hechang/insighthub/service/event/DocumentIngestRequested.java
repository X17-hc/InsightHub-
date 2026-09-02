package com.hechang.insighthub.service.event;

/** 在文档元数据事务提交后触发的异步入库事件。 */
public record DocumentIngestRequested(String workspaceId, String knowledgeBaseId, String documentId) {
}
