package com.hechang.insighthub.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ReportExportServiceImplTest {

    private final ReportExportServiceImpl service = new ReportExportServiceImpl();

    @Test
    void htmlEscapesRawMarkupAndUnsafeLinks() {
        String html = new String(service.html("<script>alert(1)</script> [x](javascript:alert(1))", "报告"), StandardCharsets.UTF_8);

        assertTrue(html.contains("&lt;script&gt;"));
        assertFalse(html.contains("href=\"javascript:"));
    }

    @Test
    void pdfEmbedsChineseFont() {
        byte[] pdf = service.pdf("# 中文报告\n内容", "中文报告");

        assertTrue(pdf.length > 1_000);
        assertTrue(new String(pdf, 0, 4, StandardCharsets.ISO_8859_1).startsWith("%PDF"));
    }
}
