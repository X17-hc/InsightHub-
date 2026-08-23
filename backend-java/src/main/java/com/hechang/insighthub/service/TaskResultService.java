package com.hechang.insighthub.service;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/** Persists one immutable report version and its citations in a single transaction. */
public interface TaskResultService {

    String saveReportAndCitations(String taskId, String workspaceId, String markdown,
            JsonNode citationsNode, JsonNode qualityNode);

    String saveReportAndCitations(String taskId, String workspaceId, String markdown,
            List<Map<String, Object>> citations, Map<String, Object> quality);
}
