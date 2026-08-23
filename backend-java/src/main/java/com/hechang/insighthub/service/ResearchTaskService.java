package com.hechang.insighthub.service;

import java.util.List;

import com.hechang.insighthub.model.dto.task.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.hechang.insighthub.model.dto.knowledge.CitationResponse;
import com.hechang.insighthub.model.entity.ResearchTask;
import com.mybatisflex.core.service.IService;

/**
 * 研究任务：异步流式 + 同步兼容 + 控制面。
 */
public interface ResearchTaskService extends IService<ResearchTask> {

    /** 异步创建任务：202 + 后台拉 Python 流 */
    CreateTaskAcceptedResponse createAsync(String workspaceId, CreateResearchTaskRequest request);

    /** 同步执行（week1/2 兼容） */
    AgentTaskResponseDto createAndRun(String workspaceId, CreateResearchTaskRequest request);

    /** 任务列表 */
    List<TaskSummaryResponse> list(String workspaceId);

    /** 任务详情 */
    TaskSummaryResponse get(String workspaceId, String taskId);

    /**
     * 删除已结束任务及其报告、引用、事件、计划修订和派发记录。
     * 仅任务创建者或工作空间管理员可执行；运行中的任务必须先取消并结束。
     */
    void delete(String workspaceId, String taskId);

    /** Latest generated report for a task. */
    ReportResponse getReport(String workspaceId, String taskId);

    List<ReportVersionResponse> listReportVersions(String workspaceId, String taskId);

    ReportResponse getReportVersion(String workspaceId, String taskId, int version);

    /** 任务引用列表（可追溯来源） */
    List<CitationResponse> listCitations(String workspaceId, String taskId);

    List<CitationResponse> listCitations(String workspaceId, String taskId, int version);

    /**
     * 历史事件列表（详情页首屏灌入；SSE 用 fromEventNo 续传）。
     *
     * @param fromEventNo 仅返回 event_no 大于该值的事件；传 0 表示全量
     */
    List<TaskEventResponse> listEvents(String workspaceId, String taskId, long fromEventNo);

    /** SSE 事件流 */
    SseEmitter streamEvents(String workspaceId, String taskId, long fromEventNo);

    /** 暂停 */
    TaskControlResponse pause(String workspaceId, String taskId);

    /** 恢复 */
    TaskControlResponse resume(String workspaceId, String taskId);

    /** 取消 */
    TaskControlResponse cancel(String workspaceId, String taskId);

    /** 失败重试 */
    CreateTaskAcceptedResponse retry(String workspaceId, String taskId);

    PlanRevisionResponse getCurrentPlan(String workspaceId, String taskId);

    List<PlanRevisionResponse> listPlanHistory(String workspaceId, String taskId);

    PlanActionResponse approvePlan(String workspaceId, String taskId, ApprovePlanRequest request, String ip);

    PlanActionResponse revisePlan(String workspaceId, String taskId, RevisePlanRequest request, String ip);
}
