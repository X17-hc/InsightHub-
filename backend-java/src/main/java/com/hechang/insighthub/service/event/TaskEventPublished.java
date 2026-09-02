package com.hechang.insighthub.service.event;

import com.hechang.insighthub.service.task.TaskEventService;
/** A persisted Java-originated task event waiting for after-commit SSE publication. */
public record TaskEventPublished(String taskId, TaskEventService.StoredEvent event) {
}
