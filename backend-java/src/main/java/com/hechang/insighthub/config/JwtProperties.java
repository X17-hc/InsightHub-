package com.hechang.insighthub.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置。
 */
@ConfigurationProperties(prefix = "insighthub.jwt")
@Data
public class JwtProperties {

    /** HMAC 密钥，生产环境必须足够长且保密 */
    private String secret = "dev-only-change-me-please-use-long-secret";

    private long accessExpireMinutes = 120;

    private long refreshExpireDays = 7;

}
