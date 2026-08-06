package com.hechang.insighthub.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Python Agent 服务连接配置。
 */
@ConfigurationProperties(prefix = "insighthub.agent")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentProperties {

    /** Agent 服务根地址，例如 http://127.0.0.1:8000 */
    private String baseUrl = "http://127.0.0.1:8000";

    /** 连接超时（毫秒） */
    private int connectTimeoutMs = 5000;

    /** 读取超时（毫秒），长任务需放宽 */
    private int readTimeoutMs = 300000;

    }
