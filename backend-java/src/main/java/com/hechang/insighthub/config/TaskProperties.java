package com.hechang.insighthub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 任务执行 / SSE / 限流配置。
 */
@ConfigurationProperties(prefix = "insighthub.task")
public class TaskProperties {

    /** 默认任务超时（秒）。 */
    private int defaultTimeoutSeconds = 300;

    /** 每用户每分钟创建任务上限。 */
    private int createRatePerMinute = 10;

    /** SSE 心跳间隔（秒）。 */
    private int sseHeartbeatSeconds = 15;

    public int getDefaultTimeoutSeconds() {
        return defaultTimeoutSeconds;
    }

    public void setDefaultTimeoutSeconds(int defaultTimeoutSeconds) {
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    public int getCreateRatePerMinute() {
        return createRatePerMinute;
    }

    public void setCreateRatePerMinute(int createRatePerMinute) {
        this.createRatePerMinute = createRatePerMinute;
    }

    public int getSseHeartbeatSeconds() {
        return sseHeartbeatSeconds;
    }

    public void setSseHeartbeatSeconds(int sseHeartbeatSeconds) {
        this.sseHeartbeatSeconds = sseHeartbeatSeconds;
    }
}
