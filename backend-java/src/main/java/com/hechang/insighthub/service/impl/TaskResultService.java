package com.hechang.insighthub.service.impl;

import java.util.UUID;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hechang.insighthub.mapper.CitationMapper;
import com.hechang.insighthub.mapper.ReportMapper;
import com.hechang.insighthub.model.entity.Citation;
import com.hechang.insighthub.model.entity.Report;
import com.mybatisflex.core.query.QueryWrapper;

/** 任务最终结果持久化：报告与引用保持同一事务调用。 */
@Service
public class TaskResultService {

    private static final Logger log = LoggerFactory.getLogger(TaskResultService.class);

    private final ReportMapper reportMapper;
    private final CitationMapper citationMapper;

    public TaskResultService(ReportMapper reportMapper, CitationMapper citationMapper) {
        this.reportMapper = reportMapper;
        this.citationMapper = citationMapper;
    }

    public String saveReportAndCitations(String taskId, String workspaceId, String markdown, JsonNode citationsNode) {
        List<Map<String, Object>> citations = new java.util.ArrayList<>();
        if (citationsNode != null && citationsNode.isArray()) {
            citationsNode.forEach(node -> {
                Map<String, Object> citation = new java.util.LinkedHashMap<>();
                node.fields().forEachRemaining(entry -> citation.put(entry.getKey(), unwrap(entry.getValue())));
                citations.add(citation);
            });
        }
        return saveReportAndCitations(taskId, workspaceId, markdown, citations);
    }

    public String saveReportAndCitations(
            String taskId, String workspaceId, String markdown, List<Map<String, Object>> citations) {
        if (markdown != null && markdown.indexOf('\uFFFD') >= 0) {
            log.error("report markdown contains U+FFFD replacement chars taskId={} workspaceId={}", taskId, workspaceId);
        }
        String reportId = "report-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Report report = new Report();
        report.setId(reportId);
        report.setTaskId(taskId);
        report.setWorkspaceId(workspaceId);
        report.setVersion(1);
        report.setTitle(extractTitle(markdown));
        report.setMarkdownContent(markdown);
        report.setStatus("READY");
        reportMapper.insert(report);

        citationMapper.deleteByQuery(QueryWrapper.create().eq(Citation::getTaskId, taskId));
        if (citations != null && !citations.isEmpty()) {
            int index = 0;
            for (Map<String, Object> source : citations) {
                index++;
                Citation citation = new Citation();
                citation.setId("cit-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
                citation.setReportId(reportId);
                citation.setTaskId(taskId);
                citation.setCitationNo(number(source.get("citationNo"), index));
                citation.setSourceTitle(string(source.get("sourceTitle")));
                citation.setSourceUri(string(source.get("sourceUri")));
                citation.setSourceType(string(source.get("sourceType")));
                citation.setDocumentId(string(source.get("documentId")));
                citation.setChunkId(string(source.get("chunkId")));
                citation.setQuotedText(string(source.get("quotedText")));
                citation.setVerified(Boolean.TRUE.equals(source.get("verified")) ? 1 : 0);
                citationMapper.insert(citation);
            }
        }
        return reportId;
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Object unwrap(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isNumber()) return node.numberValue();
        if (node.isBoolean()) return node.asBoolean();
        return node.toString();
    }

    private static String extractTitle(String markdown) {
        if (markdown == null || markdown.isBlank()) return "InsightHub Report";
        for (String line : markdown.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) return trimmed.replaceFirst("^#+\\s*", "");
        }
        return "InsightHub Report";
    }
}
