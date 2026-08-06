package com.hechang.insighthub;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * InsightHub Java 平台服务入口。
 */
@SpringBootApplication
@MapperScan("com.hechang.insighthub.mapper")
public class InsightHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(InsightHubApplication.class, args);
    }
}
