package com.hechang.insighthub.service.impl;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.hechang.insighthub.service.ReportExportService;

/**
 * 将不可变 Markdown 报告快照导出为安全 HTML/PDF。
 *
 * <p>CommonMark 禁用原始 HTML并清洗危险 URL；标题单独转义。PDF 只消费同一份
 * 安全 XHTML，并显式注册随服务打包的中文字体，避免依赖宿主机字体。导出属于
 * CPU/文件资源操作，不应放入报告持久化事务。</p>
 */
@Service
public class ReportExportServiceImpl implements ReportExportService {

    private static final String CJK_FONT_FAMILY = "Noto Sans SC";
    // OpenHTMLtoPDF/PDFBox 使用 TrueType 构建；CFF OpenType 在当前渲染器版本间
    // 兼容性不稳定，因此字体必须随应用打包并在构建时验证许可文件。
    private static final String CJK_FONT_RESOURCE = "/fonts/NotoSansSC-wght.ttf";
    private final Parser parser = Parser.builder().build();
    private final HtmlRenderer renderer = HtmlRenderer.builder()
            .escapeHtml(true)
            .sanitizeUrls(true)
            .build();

    public byte[] html(String markdown, String title) {
        String safeTitle = escape(title == null || title.isBlank() ? "InsightHub Report" : title);
        String body = renderer.render(parser.parse(markdown == null ? "" : markdown));
        // OpenHTMLtoPDF 要求 XHTML；同一文档也可作为浏览器 HTML 下载，二者不能
        // 使用不同的清洗路径，否则 PDF 与 HTML 会产生安全/内容差异。
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
