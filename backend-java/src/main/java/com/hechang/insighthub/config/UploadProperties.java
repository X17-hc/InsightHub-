package com.hechang.insighthub.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库文档上传配置。
 */
@ConfigurationProperties(prefix = "insighthub.upload")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UploadProperties {

    /** 本地上传根目录 */
    private String rootDir = "./data/uploads";

    /** 单文件大小上限（字节），默认 5MB */
    private long maxBytes = 5_242_880L;

    /** 允许的扩展名（小写，无点） */
    private List<String> allowedExtensions = List.of("txt", "md", "markdown", "pdf");
}
