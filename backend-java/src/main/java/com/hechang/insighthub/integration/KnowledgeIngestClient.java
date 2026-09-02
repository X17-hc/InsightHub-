package com.hechang.insighthub.integration;

import java.util.HashMap;
import java.util.Map;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.core.io.PathResource;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.BodyInserters;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.RequiredArgsConstructor;

/**
 * 调用 Python 知识库入库 / 清理内部 API。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeIngestClient {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestClient.class);

    /** 与 Agent 流共用 base URL、认证、UTF-8 编解码及超时配置。 */
    private final WebClient agentWebClient;

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
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("workspaceId", workspaceId);
        body.part("knowledgeBaseId", knowledgeBaseId);
        body.part("documentId", documentId);
        body.part("contentType", contentType == null ? "application/octet-stream" : contentType);
        body.part("fileName", fileName == null ? "document.bin" : fileName);
        // 仅传输文件内容；Windows 绝对路径永远不会进入跨主机协议。
        body.part("file", new PathResource(Path.of(filePath)))
                .filename(fileName == null ? "document.bin" : fileName);

        try {
            return agentWebClient.post()
                    .uri("/internal/v1/knowledge/documents/ingest-content")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(body.build()))
                    .retrieve()
                    .bodyToMono(IngestDocumentResponse.class)
                    .block();
        } catch (WebClientResponseException ex) {
            log.warn(
                    "Knowledge ingest HTTP {} documentId={}",
                    ex.getStatusCode().value(),
                    documentId);
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
            agentWebClient.post()
                    .uri("/internal/v1/knowledge/chunks/delete-by-kb")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();
        } catch (WebClientResponseException ex) {
            log.warn(
                    "Knowledge delete-by-kb HTTP {} kbId={}",
                    ex.getStatusCode().value(),
                    knowledgeBaseId);
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
