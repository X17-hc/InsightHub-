package com.hechang.insighthub.service.impl;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.hechang.insighthub.service.ReportExportService;

/** Renders immutable Markdown snapshots; raw HTML is disabled by CommonMark. */
@Service
public class ReportExportServiceImpl implements ReportExportService {

    private static final String CJK_FONT_FAMILY = "Noto Sans SC";
    // OpenHTMLtoPDF/PDFBox accepts the Google Fonts TrueType build; the CFF
    // OpenType variant is intentionally not used because it is not portable
    // across the PDF renderer versions used by this service.
    private static final String CJK_FONT_RESOURCE = "/fonts/NotoSansSC-wght.ttf";
    private final Parser parser = Parser.builder().build();
    private final HtmlRenderer renderer = HtmlRenderer.builder()
            .escapeHtml(true)
            .sanitizeUrls(true)
            .build();

    public byte[] html(String markdown, String title) {
        String safeTitle = escape(title == null || title.isBlank() ? "InsightHub Report" : title);
        String body = renderer.render(parser.parse(markdown == null ? "" : markdown));
        // OpenHTMLtoPDF consumes XHTML, while the same document remains safe to
        // download and open in a browser as an HTML export.
        String document = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><!DOCTYPE html>"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"zh-CN\"><head><meta charset=\"UTF-8\" />"
                + "<title>" + safeTitle + "</title><style>body{font-family:'" + CJK_FONT_FAMILY + "',sans-serif;"
                + "max-width:900px;margin:40px auto;line-height:1.7;color:#1f2937}pre{white-space:pre-wrap}</style>"
                + "</head><body>" + body + "</body></html>";
        return document.getBytes(StandardCharsets.UTF_8);
    }

    public byte[] pdf(String markdown, String title) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            new PdfRendererBuilder()
                    .useFont(ReportExportServiceImpl::openCjkFont, CJK_FONT_FAMILY)
                    .withHtmlContent(new String(html(markdown, title), StandardCharsets.UTF_8), null)
                    .toStream(output)
                    .run();
            return output.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("PDF_EXPORT_FAILED", ex);
        }
    }

    private static InputStream openCjkFont() {
        InputStream input = ReportExportServiceImpl.class.getResourceAsStream(CJK_FONT_RESOURCE);
        if (input == null) {
            throw new IllegalStateException("bundled CJK font is missing");
        }
        return input;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
