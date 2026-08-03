package com.insighthub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Python Agent 服务连接配置。
 */
@ConfigurationProperties(prefix = "insighthub.agent")
public class AgentProperties {

    /** Agent 服务根地址，例如 http://127.0.0.1:8000 */
    private String baseUrl = "http://127.0.0.1:8000";

    /** 连接超时（毫秒） */
    private int connectTimeoutMs = 5000;

    /** 读取超时（毫秒），长任务需放宽 */
    private int readTimeoutMs = 300000;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}
