package com.hechang.insighthub.service.impl;

import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.hechang.insighthub.mapper.CitationMapper;
import com.hechang.insighthub.mapper.ReportMapper;
import com.hechang.insighthub.mapper.ResearchTaskMapper;
import com.hechang.insighthub.model.entity.Citation;
import com.hechang.insighthub.model.entity.Report;
import com.hechang.insighthub.model.enums.QualityStatus;
import com.hechang.insighthub.service.TaskResultService;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;

/**
 * 任务结果持久化边界。
 *
 * <p>报告版本、该版本引用、报告质量快照与任务最新质量投影必须在同一短事务中
 * 成功或回滚。该类不执行 Agent、文件、PDF 或 SSE I/O，避免持有行锁期间等待
 * 外部系统。</p>
 */
@Service
@RequiredArgsConstructor
public class TaskResultServiceImpl implements TaskResultService {

    private static final Logger log = LoggerFactory.getLogger(TaskResultServiceImpl.class);

    private final ReportMapper reportMapper;
    private final CitationMapper citationMapper;
    private final ResearchTaskMapper researchTaskMapper;

    @Transactional
    public String saveReportAndCitations(String taskId, String workspaceId, String markdown,
            JsonNode citationsNode, JsonNode qualityNode) {
        List<Map<String, Object>> citations = new java.util.ArrayList<>();
        if (citationsNode != null && citationsNode.isArray()) {
            citationsNode.forEach(node -> {
                Map<String, Object> citation = new java.util.LinkedHashMap<>();
                node.fields().forEachRemaining(entry -> citation.put(entry.getKey(), unwrap(entry.getValue())));
                citations.add(citation);
            });
        }
        Map<String, Object> quality = new java.util.LinkedHashMap<>();
        if (qualityNode != null && qualityNode.isObject()) {
            qualityNode.fields().forEachRemaining(entry -> quality.put(entry.getKey(), unwrap(entry.getValue())));
        }
        return saveReportAndCitations(taskId, workspaceId, markdown, citations, quality);
    }

    @Transactional
    public String saveReportAndCitations(
            String taskId, String workspaceId, String markdown, List<Map<String, Object>> citations,
            Map<String, Object> quality) {
        if (markdown != null && markdown.indexOf('\uFFFD') >= 0) {
            log.error("report markdown contains U+FFFD replacement chars taskId={} workspaceId={}", taskId, workspaceId);
        }
        // 先锁任务行再计算版本号，使同一任务的完成回调串行化；(task_id, version)
        // 唯一约束仍是并发与程序缺陷下的最后防线，不能仅依赖“先查后插”。
        if (researchTaskMapper.findByIdAndWorkspaceForUpdate(taskId, workspaceId) == null) {
            throw new IllegalStateException("task does not exist while saving report");
        }
        Report previous = reportMapper.findLatestByTask(workspaceId, taskId);
        int nextVersion = previous == null || previous.getVersion() == null ? 1 : previous.getVersion() + 1;
        String reportId = "report-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Report report = new Report();
        report.setId(reportId);
        report.setTaskId(taskId);
        report.setWorkspaceId(workspaceId);
        report.setVersion(nextVersion);
        report.setTitle(extractTitle(markdown));
        report.setMarkdownContent(markdown);
        QualitySnapshot snapshot = qualitySnapshot(quality, citations);
        report.setStatus(QualityStatus.PASS.name().equals(snapshot.status()) ? "READY" : "LIMITED");
        report.setQualityStatus(snapshot.status());
        report.setQualitySummary(snapshot.summary());
        report.setVerifiedCitationCount(snapshot.verifiedCount());
        report.setCandidateCitationCount(snapshot.candidateCount());
        reportMapper.insert(report);

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
                String verificationStatus = normalizeVerificationStatus(source);
                citation.setVerificationStatus(verificationStatus);
                citation.setVerified("VERIFIED".equals(verificationStatus) ? 1 : 0);
                citation.setVerificationReason(string(source.get("verificationReason")));
                citation.setCanonicalUri(string(source.get("canonicalUri")));
                citation.setFinalUri(string(source.get("finalUri")));
                citation.setRetrievedAt(dateTime(source.get("retrievedAt")));
                citation.setContentHash(string(source.get("contentHash")));
                citation.setHttpStatus(numberOrNull(source.get("httpStatus")));
                citationMapper.insert(citation);
            }
        }
        researchTaskMapper.updateQuality(taskId, workspaceId, snapshot.status(), snapshot.summary(),
                snapshot.verifiedCount(), snapshot.totalCount());
        return reportId;
    }

    private static QualitySnapshot qualitySnapshot(Map<String, Object> quality, List<Map<String, Object>> citations) {
        List<Map<String, Object>> safeCitations = citations == null ? List.of() : citations;
        int verified = (int) safeCitations.stream().filter(item -> "VERIFIED".equals(normalizeVerificationStatus(item))).count();
        int synthetic = (int) safeCitations.stream()
                .filter(item -> "SYNTHETIC".equals(string(item.get("verificationStatus")))
                        || "SYNTHETIC".equalsIgnoreCase(string(item.get("sourceType"))))
                .count();
        int candidate = (int) safeCitations.stream().filter(item -> "CANDIDATE".equals(normalizeVerificationStatus(item))).count();
        String verdict = quality == null ? null : string(quality.get("verdict"));
        String status = QualityStatus.fromVerdict(verdict).name();
        String summary = quality == null ? null : truncate(string(quality.get("summary")), 1024);
        // Java 再做一次信任边界校验：Python 的 PASS 不能让 SYNTHETIC 或来源数不足
        // 的报告进入 READY。降级只改变质量，不把已完成的图执行伪装成系统异常。
        if ("PASS".equals(status) && (synthetic > 0 || verified < 3)) {
            status = QualityStatus.FAIL.name();
            summary = synthetic > 0
                    ? "quality result rejected because synthetic citations were included"
                    : "quality result rejected because fewer than three verified citations were persisted";
        }
        return new QualitySnapshot(status, summary, verified, candidate, safeCitations.size());
    }

    private static LocalDateTime dateTime(Object value) {
        if (value == null) return null;
        try {
            return LocalDateTime.ofInstant(Instant.parse(String.valueOf(value)), ZoneOffset.UTC);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Integer numberOrNull(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private record QualitySnapshot(String status, String summary, int verifiedCount,
            int candidateCount, int totalCount) {}

    private static String normalizeVerificationStatus(Map<String, Object> source) {
        String requested = string(source.get("verificationStatus"));
        if ("SYNTHETIC".equals(requested) || "SYNTHETIC".equalsIgnoreCase(string(source.get("sourceType")))) {
            return "SYNTHETIC";
        }
        if (!"VERIFIED".equals(requested)) {
            return "CANDIDATE";
        }
        String sourceType = string(source.get("sourceType"));
        if ("WEB".equalsIgnoreCase(sourceType)) {
            Integer httpStatus = numberOrNull(source.get("httpStatus"));
            return nonBlank(source.get("finalUri")) && nonBlank(source.get("contentHash"))
                    && httpStatus != null && httpStatus >= 200 && httpStatus < 300 ? "VERIFIED" : "CANDIDATE";
        }
        if ("KNOWLEDGE".equalsIgnoreCase(sourceType)) {
            return nonBlank(source.get("documentId")) && nonBlank(source.get("chunkId")) ? "VERIFIED" : "CANDIDATE";
        }
        return "CANDIDATE";
    }

    private static boolean nonBlank(Object value) {
        return value != null && !String.valueOf(value).isBlank();
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
