package com.insighthub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置。
 */
@ConfigurationProperties(prefix = "insighthub.jwt")
public class JwtProperties {

    /** HMAC 密钥，生产环境必须足够长且保密 */
    private String secret = "dev-only-change-me-please-use-long-secret";

    private long accessExpireMinutes = 120;

    private long refreshExpireDays = 7;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getAccessExpireMinutes() {
        return accessExpireMinutes;
    }

    public void setAccessExpireMinutes(long accessExpireMinutes) {
        this.accessExpireMinutes = accessExpireMinutes;
    }

    public long getRefreshExpireDays() {
        return refreshExpireDays;
    }

    public void setRefreshExpireDays(long refreshExpireDays) {
        this.refreshExpireDays = refreshExpireDays;
    }
}
