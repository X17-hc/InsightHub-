package com.hechang.insighthub.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.hechang.insighthub.mapper.CitationMapper;
import com.hechang.insighthub.mapper.ReportMapper;
import com.hechang.insighthub.mapper.ResearchTaskMapper;
import com.hechang.insighthub.model.entity.Report;
import com.hechang.insighthub.model.entity.ResearchTask;

@ExtendWith(MockitoExtension.class)
class TaskResultServiceImplTest {

    @Mock ReportMapper reportMapper;
    @Mock CitationMapper citationMapper;
    @Mock ResearchTaskMapper researchTaskMapper;

    @Test
    void passIsRejectedWhenFewerThanThreeVerifiedSourcesArePersisted() {
        when(researchTaskMapper.findByIdAndWorkspaceForUpdate("task-1", "workspace-1"))
                .thenReturn(new ResearchTask());
        TaskResultServiceImpl service = new TaskResultServiceImpl(reportMapper, citationMapper, researchTaskMapper);
        Map<String, Object> verifiedWeb = Map.of(
                "sourceType", "WEB", "verificationStatus", "VERIFIED",
                "finalUri", "https://official.example/research", "contentHash", "a".repeat(64),
                "httpStatus", 200, "quotedText", "verified text");

        service.saveReportAndCitations("task-1", "workspace-1", "# Report",
                List.of(verifiedWeb, verifiedWeb), Map.of("verdict", "PASS", "summary", "claimed pass"));

        ArgumentCaptor<Report> report = ArgumentCaptor.forClass(Report.class);
        verify(reportMapper).insert(report.capture());
        assertEquals("FAIL", report.getValue().getQualityStatus());
        assertEquals("LIMITED", report.getValue().getStatus());
        assertEquals(2, report.getValue().getVerifiedCitationCount());
        verify(researchTaskMapper).updateQuality(eq("task-1"), eq("workspace-1"), eq("FAIL"), any(), eq(2), eq(2));
    }

    @Test
    void syntheticCitationCanNeverRemainVerified() {
        when(researchTaskMapper.findByIdAndWorkspaceForUpdate("task-2", "workspace-1"))
                .thenReturn(new ResearchTask());
        TaskResultServiceImpl service = new TaskResultServiceImpl(reportMapper, citationMapper, researchTaskMapper);
        Map<String, Object> synthetic = Map.of(
                "sourceType", "SYNTHETIC", "verificationStatus", "VERIFIED", "verified", true,
                "quotedText", "historical demo text");

        service.saveReportAndCitations("task-2", "workspace-1", "# Legacy",
                List.of(synthetic), Map.of("verdict", "PASS"));

        ArgumentCaptor<com.hechang.insighthub.model.entity.Citation> citation =
                ArgumentCaptor.forClass(com.hechang.insighthub.model.entity.Citation.class);
        verify(citationMapper).insert(citation.capture());
        assertEquals("SYNTHETIC", citation.getValue().getVerificationStatus());
        assertEquals(0, citation.getValue().getVerified());
    }
}
