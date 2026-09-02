package com.hechang.insighthub.integration;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.hechang.insighthub.model.dto.task.AgentTaskResponseDto;

import lombok.RequiredArgsConstructor;

/**
 * 通过 WebClient 调用 Python Agent 服务。
 */
@Component
@RequiredArgsConstructor
public class AgentServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AgentServiceClient.class);

    private final WebClient agentWebClient;
    private final AgentTaskRequestFactory requestFactory;

    /**
     * 创建并同步执行 Agent 任务。
     *
     * @param taskId      任务 ID
     * @param workspaceId 工作空间 ID
     * @param userId      用户 ID
     * @param query       研究问题
     * @param traceId     链路 ID
     * @return Agent 同步响应
     */
    public AgentTaskResponseDto createTask(
            String taskId,
            String workspaceId,
            String userId,
            String query,
            String traceId,
            List<String> knowledgeBaseIds, boolean enableDataAnalysis) {
        Map<String, Object> body = requestFactory.forSynchronousTask(
                taskId, workspaceId, userId, query, knowledgeBaseIds, enableDataAnalysis);

        String idempotencyKey = taskId + "-attempt-1";

        try {
            return agentWebClient.post()
                    .uri("/internal/v1/agent/tasks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Trace-Id", traceId)
                    .header("X-Idempotency-Key", idempotencyKey)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(AgentTaskResponseDto.class)
                    .block();
        } catch (WebClientResponseException ex) {
            // 上游 body 仅记日志，避免泄漏到对外 API
            log.warn(
                    "Agent service HTTP {} taskId={}",
                    ex.getStatusCode().value(),
                    taskId);
            throw new IllegalStateException("Agent service error: HTTP " + ex.getStatusCode().value(), ex);
        }
    }
}
