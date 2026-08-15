package com.hechang.insighthub.service;


import com.hechang.insighthub.model.dto.task.PlanRevisionResponse;

import java.util.List;
import java.util.Map;

public interface PlanApplicationService {

    PlanRevisionResponse current(String workspaceId, String taskId);

    List<PlanRevisionResponse> history(String workspaceId, String taskId);

    void recordPlannerResult(String taskId, String workspaceId, String creatorId,
                             String runId, Map<String, Object> eventData);
}
