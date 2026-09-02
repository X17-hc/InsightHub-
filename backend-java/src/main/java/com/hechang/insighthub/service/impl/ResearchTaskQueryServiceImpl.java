package com.hechang.insighthub.service.impl;

import com.hechang.insighthub.service.task.TaskEventService;
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
import com.hechang.insighthub.model.dto.task.ReportVersionResponse;
import com.hechang.insighthub.model.dto.task.TaskEventResponse;
import com.hechang.insighthub.model.dto.task.TaskSummaryResponse;
import com.hechang.insighthub.model.entity.Citation;
import com.hechang.insighthub.model.entity.Report;
import com.hechang.insighthub.model.entity.ResearchTask;
import com.hechang.insighthub.model.entity.TaskEvent;
import com.hechang.insighthub.service.ResearchTaskQueryService;
import com.hechang.insighthub.service.WorkspaceAccessService;
import lombok.RequiredArgsConstructor;

/** 研究任务的只读查询，隔离查询 Mapper 与任务控制、执行逻辑。 */
@Service
@RequiredArgsConstructor
public class ResearchTaskQueryServiceImpl implements ResearchTaskQueryService {

    private final ResearchTaskMapper researchTaskMapper;
    private final ReportMapper reportMapper;
    private final CitationMapper citationMapper;
    private final TaskEventMapper taskEventMapper;
    private final WorkspaceAccessService accessService;
    private final TaskEventService taskEventService;

    public List<TaskSummaryResponse> list(String workspaceId) {
        requireMembership(workspaceId);
        return researchTaskMapper.listByWorkspace(workspaceId).stream()
                .map(ResearchTaskQueryServiceImpl::toSummary)
                .toList();
    }

    public TaskSummaryResponse get(String workspaceId, String taskId) {
        requireMembership(workspaceId);
        return toSummary(requireTask(workspaceId, taskId));
    }

    public ReportResponse getReport(String workspaceId, String taskId) {
        requireMembership(workspaceId);
        requireTask(workspaceId, taskId);
        Report report = reportMapper.findLatestByTask(workspaceId, taskId);
        if (report == null) throw BusinessException.notFound("report not found");
        return new ReportResponse(
                report.getId(), report.getTaskId(), report.getWorkspaceId(), report.getVersion(),
                report.getTitle(), report.getMarkdownContent(), report.getStatus(),
                report.getQualityStatus(), report.getQualitySummary(), report.getVerifiedCitationCount(),
                report.getCandidateCitationCount(),
                report.getCreatedAt(), report.getUpdatedAt());
    }

    public List<ReportVersionResponse> listReportVersions(String workspaceId, String taskId) {
        requireMembership(workspaceId);
        requireTask(workspaceId, taskId);
        return reportMapper.listByTask(workspaceId, taskId).stream()
                .map(report -> new ReportVersionResponse(report.getId(), report.getVersion(), report.getTitle(),
                        report.getStatus(), report.getQualityStatus(), report.getQualitySummary(),
                        value(report.getVerifiedCitationCount()), value(report.getCandidateCitationCount()),
                        report.getCreatedAt(), report.getUpdatedAt()))
                .toList();
    }

    public ReportResponse getReportVersion(String workspaceId, String taskId, int version) {
        requireMembership(workspaceId);
        requireTask(workspaceId, taskId);
        Report report = reportMapper.findByTaskAndVersion(workspaceId, taskId, version);
        if (report == null) throw BusinessException.notFound("report version not found");
        return new ReportResponse(report.getId(), report.getTaskId(), report.getWorkspaceId(), report.getVersion(),
                report.getTitle(), report.getMarkdownContent(), report.getStatus(),
                report.getQualityStatus(), report.getQualitySummary(), report.getVerifiedCitationCount(),
                report.getCandidateCitationCount(), report.getCreatedAt(), report.getUpdatedAt());
    }

    public List<CitationResponse> listCitations(String workspaceId, String taskId) {
        requireMembership(workspaceId);
        requireTask(workspaceId, taskId);
        Report latest = reportMapper.findLatestByTask(workspaceId, taskId);
        if (latest == null) return List.of();
        return citationMapper.listByReportId(latest.getId()).stream()
                .map(ResearchTaskQueryServiceImpl::toCitationResponse)
                .toList();
    }

    public List<CitationResponse> listCitations(String workspaceId, String taskId, int version) {
        requireMembership(workspaceId);
        requireTask(workspaceId, taskId);
        Report report = reportMapper.findByTaskAndVersion(workspaceId, taskId, version);
        if (report == null) throw BusinessException.notFound("report version not found");
        return citationMapper.listByReportId(report.getId()).stream()
                .map(ResearchTaskQueryServiceImpl::toCitationResponse)
                .toList();
    }

    public List<TaskEventResponse> listEvents(String workspaceId, String taskId, long fromEventNo) {
        requireMembership(workspaceId);
        requireTask(workspaceId, taskId);
        return taskEventMapper.listAfterEventNo(taskId, fromEventNo).stream()
                .map(row -> taskEventService.toResponse(taskId, row))
                .toList();
    }

    private void requireMembership(String workspaceId) {
        accessService.requireCurrentMember(workspaceId);
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
                citation.getChunkId(), citation.getQuotedText(), citation.getVerified(),
                citation.getVerificationStatus(), citation.getVerificationReason(), citation.getCanonicalUri(),
                citation.getFinalUri(), citation.getRetrievedAt(), citation.getContentHash(), citation.getHttpStatus(),
                citation.getCreatedAt());
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
        response.setQualityStatus(row.getQualityStatus());
        response.setQualitySummary(row.getQualitySummary());
        response.setVerifiedCitationCount(value(row.getVerifiedCitationCount()));
        response.setTotalCitationCount(value(row.getTotalCitationCount()));
        response.setEnableDataAnalysis(Boolean.TRUE.equals(row.getEnableDataAnalysis()));
        if (row.getCreatedAt() != null) response.setCreatedAt(Timestamp.valueOf(row.getCreatedAt()));
        return response;
    }

    private static int value(Integer input) {
        return input == null ? 0 : input;
    }
}
