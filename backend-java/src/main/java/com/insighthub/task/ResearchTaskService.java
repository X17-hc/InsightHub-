package com.insighthub.task;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.insighthub.config.DemoProperties;
import com.insighthub.integration.AgentServiceClient;
import com.insighthub.web.dto.AgentTaskResponseDto;

/**
 * 第 1 周研究任务编排：落库 → 调 Python → 写事件/报告。
 */
@Service
public class ResearchTaskService {

    private static final Logger log = LoggerFactory.getLogger(ResearchTaskService.class);

    private final AgentServiceClient agentServiceClient;
    private final TaskRepository taskRepository;
    private final DemoProperties demoProperties;

    public ResearchTaskService(
            AgentServiceClient agentServiceClient,
            TaskRepository taskRepository,
            DemoProperties demoProperties) {
        this.agentServiceClient = agentServiceClient;
        this.taskRepository = taskRepository;
        this.demoProperties = demoProperties;
    }

    /**
     * 创建研究任务并同步等待 Agent 完成。
     *
     * @param query 用户研究主题
     * @return 含报告与事件的响应
     */
    @Transactional
    public AgentTaskResponseDto createAndRun(String query) {
        String taskId = "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String traceId = "trace-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String workspaceId = demoProperties.getWorkspaceId();
        String userId = demoProperties.getUserId();

        taskRepository.insertCreatedTask(taskId, workspaceId, userId, query, traceId);
        log.info("Created research task {} traceId={}", taskId, traceId);

        AgentTaskResponseDto response = agentServiceClient.createTask(
                taskId, workspaceId, userId, query, traceId);
        if (response == null) {
            throw new IllegalStateException("Agent service returned empty body");
        }
        response.setTraceId(traceId);

        String status = response.getStatus() == null ? "FAILED" : response.getStatus();
        String errorCode = null;
        String errorMessage = null;
        if (response.getError() != null) {
            errorCode = String.valueOf(response.getError().getOrDefault("code", "AGENT_ERROR"));
            errorMessage = String.valueOf(response.getError().getOrDefault("message", ""));
        }
        taskRepository.updateTaskFinished(taskId, status, response.getRunId(), errorCode, errorMessage);
        taskRepository.insertEvents(taskId, response.getEvents());

        if ("COMPLETED".equalsIgnoreCase(status) && response.getReportMarkdown() != null) {
            String reportId = "report-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            String title = extractTitle(response.getReportMarkdown());
            taskRepository.insertReport(reportId, taskId, workspaceId, response.getReportMarkdown(), title);
        }

        return response;
    }

    private static String extractTitle(String markdown) {
        for (String line : markdown.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                return trimmed.replaceFirst("^#+\\s*", "");
            }
        }
        return "InsightHub Report";
    }
}
