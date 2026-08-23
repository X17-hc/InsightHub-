package com.hechang.insighthub.service;

import java.util.List;

import com.hechang.insighthub.model.dto.knowledge.CitationResponse;
import com.hechang.insighthub.model.dto.task.ReportResponse;
import com.hechang.insighthub.model.dto.task.ReportVersionResponse;
import com.hechang.insighthub.model.dto.task.TaskEventResponse;
import com.hechang.insighthub.model.dto.task.TaskSummaryResponse;

/** Read-only queries for workspace-scoped research tasks and their projections. */
public interface ResearchTaskQueryService {

    List<TaskSummaryResponse> list(String workspaceId);

    TaskSummaryResponse get(String workspaceId, String taskId);

    ReportResponse getReport(String workspaceId, String taskId);

    List<ReportVersionResponse> listReportVersions(String workspaceId, String taskId);

    ReportResponse getReportVersion(String workspaceId, String taskId, int version);

    List<CitationResponse> listCitations(String workspaceId, String taskId);

    List<CitationResponse> listCitations(String workspaceId, String taskId, int version);

    List<TaskEventResponse> listEvents(String workspaceId, String taskId, long fromEventNo);
}
