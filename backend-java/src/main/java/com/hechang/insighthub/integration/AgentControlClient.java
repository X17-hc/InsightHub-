package com.hechang.insighthub.integration;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import lombok.RequiredArgsConstructor;

/**
 * Agent 控制面客户端。
 *
 * <p>Java 与 Ubuntu Agent 使用各自本机 Redis；暂停/恢复/取消必须通过内部 API
 * 写入 Agent 所在 Redis，不能假设两个进程共享同一个 127.0.0.1。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentControlClient {

    private static final Logger log = LoggerFactory.getLogger(AgentControlClient.class);

    private final WebClient agentWebClient;

    /** 写入 Agent 控制字；失败时 fail-closed，调用方不得只更新 Java 状态。 */
    public void setControl(String taskId, String value, int ttlSeconds) {
        try {
            agentWebClient.put()
                    .uri("/internal/v1/agent/tasks/{taskId}/control", taskId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("value", value, "ttlSeconds", ttlSeconds))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (RuntimeException ex) {
            log.warn("Agent control unavailable taskId={} value={} errorType={}",
                    taskId, value, ex.getClass().getSimpleName());
            throw new IllegalStateException("agent task control unavailable", ex);
        }
    }
}
