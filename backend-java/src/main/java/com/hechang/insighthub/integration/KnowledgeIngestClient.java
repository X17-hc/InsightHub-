package com.hechang.insighthub.integration;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hechang.insighthub.config.AgentProperties;

import lombok.Data;
import reactor.netty.http.client.HttpClient;

/**
 * 调用 Python 知识库入库 / 清理内部 API。
 */
@Component
public class KnowledgeIngestClient {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestClient.class);

    /** 连接超时：5s；读取超时：120s（解析+embedding） */
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 120_000;

    private final WebClient webClient;

    public KnowledgeIngestClient(AgentProperties agentProperties) {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMillis(READ_TIMEOUT_MS))
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS);
        this.webClient = WebClient.builder()
                .baseUrl(agentProperties.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /**
     * 触发 Python 解析并写入 PGVector。
     *
     * @return 入库结果（含 chunkCount）
     */
    public IngestDocumentResponse ingestDocument(
            String workspaceId,
            String knowledgeBaseId,
            String documentId,
            String filePath,
            String contentType,
            String fileName) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceId", workspaceId);
        body.put("knowledgeBaseId", knowledgeBaseId);
        body.put("documentId", documentId);
        body.put("filePath", filePath);
        body.put("contentType", contentType);
        body.put("fileName", fileName);

        try {
            return webClient.post()
                    .uri("/internal/v1/knowledge/documents/ingest")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(IngestDocumentResponse.class)
                    .block();
        } catch (WebClientResponseException ex) {
            log.warn(
                    "Knowledge ingest HTTP {} documentId={} body={}",
                    ex.getStatusCode().value(),
                    documentId,
                    ex.getResponseBodyAsString());
            throw new IllegalStateException(
                    "Knowledge ingest error: HTTP " + ex.getStatusCode().value(), ex);
        }
    }

    /**
     * 删除指定知识库在 PGVector 中的全部片段。
     */
    public void deleteChunksByKb(String workspaceId, String knowledgeBaseId) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceId", workspaceId);
        body.put("knowledgeBaseId", knowledgeBaseId);

        try {
            webClient.post()
                    .uri("/internal/v1/knowledge/chunks/delete-by-kb")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();
        } catch (WebClientResponseException ex) {
            log.warn(
                    "Knowledge delete-by-kb HTTP {} kbId={} body={}",
                    ex.getStatusCode().value(),
                    knowledgeBaseId,
                    ex.getResponseBodyAsString());
            throw new IllegalStateException(
                    "Knowledge delete-by-kb error: HTTP " + ex.getStatusCode().value(), ex);
        }
    }

    /** Python ingest 响应体。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IngestDocumentResponse {
        private String documentId;
        private int chunkCount;
        private String embeddingModel;
    }
}
