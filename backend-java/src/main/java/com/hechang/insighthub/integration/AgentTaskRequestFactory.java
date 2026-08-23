package com.hechang.insighthub.integration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.hechang.insighthub.service.TaskDispatchCommand;

/**
 * Builds the versioned Java-to-Agent task protocol in one place.
 *
 * <p>The Agent API currently accepts JSON objects rather than a generated client
 * model. Keeping that detail here prevents the synchronous and NDJSON clients
 * from silently drifting apart.</p>
 */
@Component
public class AgentTaskRequestFactory {

    private static final int MAX_STEPS = 20;
    private static final int MAX_PARALLELISM = 3;
    private static final int MAX_CRITIC_ROUNDS = 2;

    public Map<String, Object> forSynchronousTask(
            String taskId,
            String workspaceId,
            String userId,
            String query,
            List<String> knowledgeBaseIds,
            boolean enableDataAnalysis) {
        return taskBody(taskId, workspaceId, userId, query, "PLAN", 1, null,
                knowledgeBaseIds, taskConfig(enableDataAnalysis, false, null, null));
    }

    public Map<String, Object> forStreamTask(
            String taskId,
            String workspaceId,
            String userId,
            String query,
            int timeoutSeconds,
            Long nextEventId,
            List<String> knowledgeBaseIds,
            boolean enableDataAnalysis) {
        return taskBody(taskId, workspaceId, userId, query, "PLAN", 1, null,
                knowledgeBaseIds, taskConfig(enableDataAnalysis, true, timeoutSeconds, nextEventId));
    }

    public Map<String, Object> forDispatchCommand(
            TaskDispatchCommand command,
            int timeoutSeconds,
            Long nextEventId) {
        Map<String, Object> body = taskBody(
                command.taskId(),
                command.workspaceId(),
                command.userId(),
                command.query(),
                command.phase(),
                command.planRevision(),
                command.runId(),
                command.knowledgeBaseIds(),
                taskConfig(command.enableDataAnalysis(), true, timeoutSeconds, nextEventId));
        if (command.revisionInstruction() != null) {
            body.put("revisionInstruction", command.revisionInstruction());
        }
        return body;
    }

    private static Map<String, Object> taskBody(
            String taskId,
            String workspaceId,
            String userId,
            String query,
            String phase,
            int planRevision,
            String runId,
            List<String> knowledgeBaseIds,
            Map<String, Object> config) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", taskId);
        body.put("workspaceId", workspaceId);
        body.put("userId", userId);
        body.put("query", query);
        body.put("phase", phase);
        body.put("planRevision", planRevision);
        if (runId != null) {
            body.put("runId", runId);
        }
        body.put("knowledgeBaseIds", knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds));
        body.put("config", config);
        return body;
    }

    private static Map<String, Object> taskConfig(
            boolean enableDataAnalysis,
            boolean requirePlanApproval,
            Integer timeoutSeconds,
            Long nextEventId) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("maxSteps", MAX_STEPS);
        config.put("maxParallelism", MAX_PARALLELISM);
        config.put("requirePlanApproval", requirePlanApproval);
        config.put("enableWebSearch", true);
        config.put("maxCriticRounds", MAX_CRITIC_ROUNDS);
        config.put("enableDataAnalysis", enableDataAnalysis);
        if (timeoutSeconds != null) {
            config.put("timeoutSeconds", timeoutSeconds);
        }
        if (nextEventId != null && nextEventId > 1) {
            config.put("nextEventId", nextEventId);
        }
        return config;
    }
}
