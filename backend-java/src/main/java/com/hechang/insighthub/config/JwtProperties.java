package com.hechang.insighthub.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * JWT 配置。
 */
@ConfigurationProperties(prefix = "insighthub.jwt")
@Validated
@Data
public class JwtProperties {

    /** HMAC 密钥，生产环境必须足够长且保密 */
    @NotBlank
    @Size(min = 32)
    private String secret = "";

    /** 令牌签发方；解析时必须完全匹配。 */
    @NotBlank
    private String issuer = "insighthub";

    /** 令牌目标受众；防止其他系统签发的同密钥令牌被误接受。 */
    @NotBlank
    private String audience = "insighthub-web";

    @Positive
    private long accessExpireMinutes = 120;

    @Positive
    private long refreshExpireDays = 7;

}
