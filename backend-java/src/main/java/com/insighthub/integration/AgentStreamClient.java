package com.insighthub.integration;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insighthub.config.AgentProperties;

import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

/**
 * 消费 Python Agent NDJSON 流。
 */
@Component
public class AgentStreamClient {

    private static final Logger log = LoggerFactory.getLogger(AgentStreamClient.class);
    /** 单行缓冲上限，防止无换行恶意载荷撑爆堆 */
    private static final int MAX_BUFFER_CHARS = 1_048_576;
    private static final int MAX_BAD_LINES = 20;

    private final WebClient agentWebClient;
    private final ObjectMapper objectMapper;
    private final AgentProperties agentProperties;

    public AgentStreamClient(
            WebClient agentWebClient,
            ObjectMapper objectMapper,
            AgentProperties agentProperties) {
        this.agentWebClient = agentWebClient;
        this.objectMapper = objectMapper;
        this.agentProperties = agentProperties;
    }

    /**
     * 启动流式任务并逐行回调 JSON 节点。
     *
     * @param nextEventId    Python 侧起始 eventId（通常为 DB max+1）；null 表示从 1 起
     * @param idempotencyKey 幂等键（retry 应使用新 key）
     */
    public void streamTask(
            String taskId,
            String workspaceId,
            String userId,
            String query,
            String traceId,
            int timeoutSec,
            Long nextEventId,
            String idempotencyKey,
            Consumer<JsonNode> onLine) {
        Map<String, Object> body = buildBody(taskId, workspaceId, userId, query, timeoutSec, nextEventId);
        String key = idempotencyKey == null || idempotencyKey.isBlank()
                ? taskId + "-stream-1"
                : idempotencyKey;
        Flux<DataBuffer> flux = clientForTimeout(timeoutSec).post()
                .uri("/internal/v1/agent/tasks/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.parseMediaType("application/x-ndjson"))
                .header("X-Trace-Id", traceId)
                .header("X-Idempotency-Key", key)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(DataBuffer.class);

        consumeNdjson(flux, onLine);
    }

    /**
     * 从 Checkpoint 恢复流。
     */
    public void resumeTask(
            String taskId,
            String runId,
            String traceId,
            int timeoutSec,
            Consumer<JsonNode> onLine) {
        Map<String, Object> body = new HashMap<>();
        if (runId != null) {
            body.put("runId", runId);
        }
        if (traceId != null) {
            body.put("traceId", traceId);
        }
        Flux<DataBuffer> flux = clientForTimeout(timeoutSec).post()
                .uri("/internal/v1/agent/tasks/{taskId}/resume", taskId)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.parseMediaType("application/x-ndjson"))
                .header("X-Trace-Id", traceId == null ? "" : traceId)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(DataBuffer.class);

        consumeNdjson(flux, onLine);
    }

    /** 按任务超时构造带 responseTimeout 的 WebClient（超时 + 缓冲）。 */
    private WebClient clientForTimeout(int timeoutSec) {
        long readMs = Math.max(
                agentProperties.getReadTimeoutMs(),
                (Math.max(1, timeoutSec) + 60L) * 1000L);
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMillis(readMs))
                .option(
                        io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        agentProperties.getConnectTimeoutMs());
        return agentWebClient.mutate()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    private void consumeNdjson(Flux<DataBuffer> flux, Consumer<JsonNode> onLine) {
        StringBuilder buf = new StringBuilder();
        AtomicInteger badLines = new AtomicInteger();
        try {
            flux.map(db -> {
                        byte[] bytes = new byte[db.readableByteCount()];
                        db.read(bytes);
                        DataBufferUtils.release(db);
                        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    })
                    .doOnNext(chunk -> {
                        buf.append(chunk);
                        if (buf.length() > MAX_BUFFER_CHARS) {
                            throw new IllegalStateException("NDJSON buffer exceeded " + MAX_BUFFER_CHARS + " chars");
                        }
                        int idx;
                        while ((idx = indexOfNewline(buf)) >= 0) {
                            String line = buf.substring(0, idx).trim();
                            buf.delete(0, idx + 1);
                            if (line.isEmpty()) {
                                continue;
                            }
                            try {
                                JsonNode node = objectMapper.readTree(line);
                                onLine.accept(node);
                            } catch (Exception ex) {
                                int n = badLines.incrementAndGet();
                                log.warn("Bad NDJSON line skipped ({}/{}): {}", n, MAX_BAD_LINES, abbreviate(line), ex);
                                if (n > MAX_BAD_LINES) {
                                    throw new IllegalStateException("too many bad NDJSON lines");
                                }
                            }
                        }
                    })
                    .blockLast();
            String rest = buf.toString().trim();
            if (!rest.isEmpty()) {
                try {
                    onLine.accept(objectMapper.readTree(rest));
                } catch (Exception ex) {
                    int n = badLines.incrementAndGet();
                    log.warn("Bad trailing NDJSON skipped ({}/{})", n, MAX_BAD_LINES, ex);
                    if (n > MAX_BAD_LINES) {
                        throw new IllegalStateException("too many bad NDJSON lines");
                    }
                }
            }
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Agent stream failed: " + ex.getMessage(), ex);
        }
    }

    private static String abbreviate(String line) {
        if (line == null) {
            return "";
        }
        return line.length() <= 200 ? line : line.substring(0, 200) + "...";
    }

    private static int indexOfNewline(StringBuilder buf) {
        for (int i = 0; i < buf.length(); i++) {
            if (buf.charAt(i) == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, Object> buildBody(
            String taskId,
            String workspaceId,
            String userId,
            String query,
            int timeoutSec,
            Long nextEventId) {
        Map<String, Object> config = new HashMap<>();
        config.put("maxSteps", 20);
        config.put("maxParallelism", 3);
        config.put("requirePlanApproval", false);
        config.put("enableWebSearch", true);
        config.put("timeoutSeconds", timeoutSec);
        if (nextEventId != null && nextEventId > 1) {
            config.put("nextEventId", nextEventId);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("taskId", taskId);
        body.put("workspaceId", workspaceId);
        body.put("userId", userId);
        body.put("query", query);
        body.put("knowledgeBaseIds", List.of());
        body.put("config", config);
        return body;
    }
}
