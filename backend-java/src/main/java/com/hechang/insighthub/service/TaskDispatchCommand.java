package com.hechang.insighthub.service;

import java.util.List;

public record TaskDispatchCommand(
        String taskId, String workspaceId, String userId, String query, String traceId,
        String runId, String phase, int planRevision, String revisionInstruction,
        String approvedPlanHash, List<String> knowledgeBaseIds, boolean enableDataAnalysis) {}
