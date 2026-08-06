package com.hechang.insighthub.service;

import java.util.List;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.hechang.insighthub.model.dto.task.AgentTaskResponseDto;
import com.hechang.insighthub.model.dto.task.CreateTaskAcceptedResponse;
import com.hechang.insighthub.model.dto.task.TaskControlResponse;
import com.hechang.insighthub.model.dto.task.TaskSummaryResponse;
import com.hechang.insighthub.model.entity.ResearchTask;
import com.mybatisflex.core.service.IService;

/**
 * 研究任务：异步流式 + 同步兼容 + 控制面。
 */
public interface ResearchTaskService extends IService<ResearchTask> {

    /** 异步创建任务：202 + 后台拉 Python 流 */
    CreateTaskAcceptedResponse createAsync(String workspaceId, String query);

    /** 同步执行（week1/2 兼容） */
    AgentTaskResponseDto createAndRun(String workspaceId, String query);

    /** 任务列表 */
    List<TaskSummaryResponse> list(String workspaceId);

    /** 任务详情 */
    TaskSummaryResponse get(String workspaceId, String taskId);

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
}
