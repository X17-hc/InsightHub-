package com.hechang.insighthub.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/** 跨域白名单配置。 */
@Data
@Validated
@ConfigurationProperties(prefix = "insighthub.cors")
public class CorsProperties {

    @NotEmpty
    private List<@NotBlank String> allowedOrigins;
}
