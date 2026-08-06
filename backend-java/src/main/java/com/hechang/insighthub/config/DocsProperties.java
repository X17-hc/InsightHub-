package com.hechang.insighthub.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * API 文档（Knife4j / OpenAPI）访问控制。
 */
@ConfigurationProperties(prefix = "insighthub.docs")
@Data
public class DocsProperties {

    /** true 时匿名可访问 /doc.html 与 OpenAPI；生产建议 false。 */
    private boolean publicAccess = true;


}
