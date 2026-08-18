package com.hechang.insighthub.service.impl;

/** A persisted Java-originated task event waiting for after-commit SSE publication. */
public record TaskEventPublished(String taskId, TaskEventService.StoredEvent event) {
}
