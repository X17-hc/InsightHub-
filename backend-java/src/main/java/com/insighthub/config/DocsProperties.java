package com.insighthub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * API 文档（Knife4j / OpenAPI）访问控制。
 */
@ConfigurationProperties(prefix = "insighthub.docs")
public class DocsProperties {

    /** true 时匿名可访问 /doc.html 与 OpenAPI；生产建议 false。 */
    private boolean publicAccess = true;

    public boolean isPublicAccess() {
        return publicAccess;
    }

    public void setPublicAccess(boolean publicAccess) {
        this.publicAccess = publicAccess;
    }
}
