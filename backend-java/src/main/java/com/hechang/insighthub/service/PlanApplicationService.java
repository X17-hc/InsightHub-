package com.hechang.insighthub.service;


import com.hechang.insighthub.model.dto.task.PlanRevisionResponse;
import com.hechang.insighthub.model.dto.task.PlanActionResponse;
import com.hechang.insighthub.model.dto.task.ApprovePlanRequest;
import com.hechang.insighthub.model.dto.task.RevisePlanRequest;

import java.util.List;
import java.util.Map;

public interface PlanApplicationService {

    PlanRevisionResponse current(String workspaceId, String taskId);

    List<PlanRevisionResponse> history(String workspaceId, String taskId);

    void recordPlannerResult(String taskId, String workspaceId, String creatorId,
                             String runId, Map<String, Object> eventData);

    PlanActionResponse approve(String workspaceId, String taskId, ApprovePlanRequest request, String ip);

    PlanActionResponse revise(String workspaceId, String taskId, RevisePlanRequest request, String ip);
}
