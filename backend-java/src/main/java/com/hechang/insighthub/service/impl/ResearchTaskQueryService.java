package com.hechang.insighthub.service.impl;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.stereotype.Service;

import com.hechang.insighthub.exception.BusinessException;
import com.hechang.insighthub.mapper.CitationMapper;
import com.hechang.insighthub.mapper.ReportMapper;
import com.hechang.insighthub.mapper.ResearchTaskMapper;
import com.hechang.insighthub.mapper.TaskEventMapper;
import com.hechang.insighthub.model.dto.knowledge.CitationResponse;
import com.hechang.insighthub.model.dto.task.ReportResponse;
import com.hechang.insighthub.model.dto.task.TaskEventResponse;
import com.hechang.insighthub.model.dto.task.TaskSummaryResponse;
import com.hechang.insighthub.model.entity.Citation;
import com.hechang.insighthub.model.entity.Report;
import com.hechang.insighthub.model.entity.ResearchTask;
import com.hechang.insighthub.security.SecurityUtils;
import com.hechang.insighthub.service.WorkspaceAccessService;

/** 研究任务的只读查询，隔离查询 Mapper 与任务控制、执行逻辑。 */
@Service
public class ResearchTaskQueryService {

    private final ResearchTaskMapper researchTaskMapper;
    private final ReportMapper reportMapper;
    private final CitationMapper citationMapper;
    private final TaskEventMapper taskEventMapper;
    private final WorkspaceAccessService accessService;
    private final TaskEventService taskEventService;

    public ResearchTaskQueryService(
            ResearchTaskMapper researchTaskMapper,
            ReportMapper reportMapper,
            CitationMapper citationMapper,
            TaskEventMapper taskEventMapper,
            WorkspaceAccessService accessService,
            TaskEventService taskEventService) {
        this.researchTaskMapper = researchTaskMapper;
        this.reportMapper = reportMapper;
        this.citationMapper = citationMapper;
        this.taskEventMapper = taskEventMapper;
        this.accessService = accessService;
        this.taskEventService = taskEventService;
    }

    public List<TaskSummaryResponse> list(String workspaceId) {
        requireMembership(workspaceId);
        return researchTaskMapper.listByWorkspace(workspaceId).stream()
                .map(ResearchTaskQueryService::toSummary)
                .toList();
    }

    public TaskSummaryResponse get(String workspaceId, String taskId) {
        requireMembership(workspaceId);
        return toSummary(requireTask(workspaceId, taskId));
    }

    public ReportResponse getReport(String workspaceId, String taskId) {
        requireMembership(workspaceId);
        requireTask(workspaceId, taskId);
        Report report = reportMapper.findLatestByTaskAndWorkspace(taskId, workspaceId);
        if (report == null) throw BusinessException.notFound("report not found");
        return new ReportResponse(
                report.getId(), report.getTaskId(), report.getWorkspaceId(), report.getVersion(),
                report.getTitle(), report.getMarkdownContent(), report.getStatus(),
                report.getCreatedAt(), report.getUpdatedAt());
    }

    public List<CitationResponse> listCitations(String workspaceId, String taskId) {
        requireMembership(workspaceId);
        requireTask(workspaceId, taskId);
        return citationMapper.listByTaskId(taskId).stream()
                .map(ResearchTaskQueryService::toCitationResponse)
                .toList();
    }

    public List<TaskEventResponse> listEvents(String workspaceId, String taskId, long fromEventNo) {
        requireMembership(workspaceId);
        requireTask(workspaceId, taskId);
        return taskEventMapper.listAfterEventNo(taskId, Math.max(0L, fromEventNo)).stream()
                .map(row -> taskEventService.toResponse(taskId, row))
                .toList();
    }

    private void requireMembership(String workspaceId) {
        accessService.requireMember(workspaceId, SecurityUtils.requireUserId());
    }

    private ResearchTask requireTask(String workspaceId, String taskId) {
        ResearchTask task = researchTaskMapper.findByIdAndWorkspace(taskId, workspaceId);
        if (task == null) throw BusinessException.notFound("task not found");
        return task;
    }

    private static CitationResponse toCitationResponse(Citation citation) {
        return new CitationResponse(
                citation.getId(), citation.getReportId(), citation.getTaskId(), citation.getCitationNo(),
                citation.getSourceTitle(), citation.getSourceUri(), citation.getSourceType(), citation.getDocumentId(),
                citation.getChunkId(), citation.getQuotedText(), citation.getVerified(), citation.getCreatedAt());
    }

    private static TaskSummaryResponse toSummary(ResearchTask row) {
        TaskSummaryResponse response = new TaskSummaryResponse();
        response.setTaskId(row.getId());
        response.setWorkspaceId(row.getWorkspaceId());
        response.setCreatorId(row.getCreatorId());
        response.setQuery(row.getQuery());
        response.setStatus(row.getStatus());
        response.setProgress(row.getProgress() == null ? 0 : row.getProgress());
        response.setTraceId(row.getTraceId());
        response.setRunId(row.getCurrentRunId());
        response.setErrorCode(row.getErrorCode());
        response.setErrorMessage(row.getErrorMessage());
        if (row.getCreatedAt() != null) response.setCreatedAt(Timestamp.valueOf(row.getCreatedAt()));
        return response;
    }
}
