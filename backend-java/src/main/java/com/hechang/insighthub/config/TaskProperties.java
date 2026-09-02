package com.hechang.insighthub.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;

/**
 * 任务执行 / SSE / 限流配置。
 */
@ConfigurationProperties(prefix = "insighthub.task")
@Validated
@Data
public class TaskProperties {

    /** 默认任务超时（秒）。 */
    @Positive
    private int defaultTimeoutSeconds = 900;

    /** 每用户每分钟创建任务上限。 */
    @Positive
    private int createRatePerMinute = 10;

    /** SSE 心跳间隔（秒）。 */
    @Positive
    private int sseHeartbeatSeconds = 15;

    /** 单个任务允许的同时在线 SSE 连接数。 */
    @Positive
    private int sseMaxConnectionsPerTask = 10;

    /** 单实例允许的 SSE 连接总数。 */
    @Positive
    private int sseMaxConnectionsTotal = 200;

    /** 单条 SSE 连接最长存活时间（秒）；客户端会携带 Last-Event-ID 自动重连。 */
    @Positive
    private int sseConnectionTimeoutSeconds = 1800;

    /** 可靠派发命令允许的最大尝试次数。 */
    @Positive
    private int dispatchMaxAttempts = 5;

}
