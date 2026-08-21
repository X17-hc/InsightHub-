package com.hechang.insighthub.service.impl;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

/** Renders immutable Markdown snapshots; raw HTML is disabled by CommonMark. */
@Service
public class ReportExportService {

    private final Parser parser = Parser.builder().build();
    private final HtmlRenderer renderer = HtmlRenderer.builder().escapeHtml(true).build();

    public byte[] html(String markdown, String title) {
        String safeTitle = escape(title == null || title.isBlank() ? "InsightHub Report" : title);
        String body = renderer.render(parser.parse(markdown == null ? "" : markdown));
        String document = "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">"
                + "<title>" + safeTitle + "</title><style>body{font-family:'Noto Sans CJK SC','Microsoft YaHei',sans-serif;"
                + "max-width:900px;margin:40px auto;line-height:1.7;color:#1f2937}pre{white-space:pre-wrap}</style>"
                + "</head><body>" + body + "</body></html>";
        return document.getBytes(StandardCharsets.UTF_8);
    }

    public byte[] pdf(String markdown, String title) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            new PdfRendererBuilder().withHtmlContent(new String(html(markdown, title), StandardCharsets.UTF_8), null)
                    .toStream(output).run();
            return output.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("PDF_EXPORT_FAILED", ex);
        }
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
