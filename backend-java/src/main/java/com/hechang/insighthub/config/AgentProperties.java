package com.hechang.insighthub.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Python Agent 服务连接配置。
 */
@ConfigurationProperties(prefix = "insighthub.agent")
@Validated
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentProperties {

    /** Agent 服务根地址，例如 http://127.0.0.1:8000 */
    @NotBlank
    private String baseUrl = "http://192.168.100.128:8000";

    /** 连接超时（毫秒） */
    @Positive
    private int connectTimeoutMs = 5000;

    /** 读取超时（毫秒），长任务需放宽 */
    @Positive
    private int readTimeoutMs = 300000;

    /** 调用 Python /internal/v1 接口的共享密钥 */
    @NotBlank
    private String internalToken = "";

}
