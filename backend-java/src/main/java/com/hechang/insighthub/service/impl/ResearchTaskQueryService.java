package com.hechang.insighthub.service.impl;

import jakarta.annotation.Resource;
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
import com.hechang.insighthub.model.entity.TaskEvent;
import com.hechang.insighthub.service.WorkspaceAccessService;
import com.mybatisflex.core.query.QueryWrapper;

/** 研究任务的只读查询，隔离查询 Mapper 与任务控制、执行逻辑。 */
@Service
public class ResearchTaskQueryService {

    @Resource
    private ResearchTaskMapper researchTaskMapper;
    @Resource
    private ReportMapper reportMapper;
    @Resource
    private CitationMapper citationMapper;
    @Resource
    private TaskEventMapper taskEventMapper;
    @Resource
    private WorkspaceAccessService accessService;
    @Resource
    private TaskEventService taskEventService;

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
        Report report = reportMapper.selectOneByQuery(QueryWrapper.create()
                .eq(Report::getTaskId, taskId)
                .eq(Report::getWorkspaceId, workspaceId)
                .orderBy(Report::getVersion, false)
                .orderBy(Report::getCreatedAt, false)
                .limit(1));
        if (report == null) throw BusinessException.notFound("report not found");
        return new ReportResponse(
                report.getId(), report.getTaskId(), report.getWorkspaceId(), report.getVersion(),
                report.getTitle(), report.getMarkdownContent(), report.getStatus(),
                report.getCreatedAt(), report.getUpdatedAt());
    }

    public List<CitationResponse> listCitations(String workspaceId, String taskId) {
        requireMembership(workspaceId);
        requireTask(workspaceId, taskId);
        return citationMapper.selectListByQuery(QueryWrapper.create()
                .eq(Citation::getTaskId, taskId)
                .orderBy(Citation::getCitationNo, true)).stream()
                .map(ResearchTaskQueryService::toCitationResponse)
                .toList();
    }

    public List<TaskEventResponse> listEvents(String workspaceId, String taskId, long fromEventNo) {
        requireMembership(workspaceId);
        requireTask(workspaceId, taskId);
        return taskEventMapper.selectListByQuery(QueryWrapper.create()
                .eq(TaskEvent::getTaskId, taskId)
                .gt(TaskEvent::getEventNo, Math.max(0L, fromEventNo))
                .orderBy(TaskEvent::getEventNo, true)).stream()
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
