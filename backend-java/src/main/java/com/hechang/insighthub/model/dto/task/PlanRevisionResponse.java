package com.hechang.insighthub.model.dto.task;

import java.time.LocalDateTime;
import com.fasterxml.jackson.databind.JsonNode;


public record PlanRevisionResponse(
        String id,
        String taskId,
        String workspaceId,
        Integer revisionNo,
        String status,
        JsonNode plan,
        String planHash,
        String revisionInstruction,
        String createdBy,
        String approvedBy,
        String approvalRemark,
        LocalDateTime createdAt,
        LocalDateTime approvedAt
) {}
