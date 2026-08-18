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

    @Positive
    private long accessExpireMinutes = 120;

    @Positive
    private long refreshExpireDays = 7;

}
