package com.hechang.insighthub.redis;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 任务控制字与事件 Pub/Sub。
 */
@Component
public class TaskControlRedis {

    public static final String CONTROL_RUNNING = "RUNNING";
    public static final String CONTROL_PAUSED = "PAUSED";
    public static final String CONTROL_CANCELLED = "CANCELLED";

    private static final Logger log = LoggerFactory.getLogger(TaskControlRedis.class);

    private final StringRedisTemplate redisTemplate;

    public TaskControlRedis(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public static String controlKey(String taskId) {
        return "ih:task:" + taskId + ":control";
    }

    public static String eventsChannel(String taskId) {
        return "ih:task:" + taskId + ":events";
    }

    /**
     * 写入控制字。Redis 故障时仅打错误日志（创建路径不因控制字失败而整单失败）。
     *
     * @return false 表示未写入 Redis
     */
    public boolean setControl(String taskId, String value, int ttlSeconds) {
        try {
            redisTemplate.opsForValue().set(
                    controlKey(taskId),
                    value,
                    Duration.ofSeconds(Math.max(60, ttlSeconds)));
            return true;
        } catch (Exception ex) {
            log.error("Redis setControl failed taskId={} (degraded)", taskId, ex);
            return false;
        }
    }

    public String getControl(String taskId) {
        try {
            String v = redisTemplate.opsForValue().get(controlKey(taskId));
            return v == null ? CONTROL_RUNNING : v;
        } catch (Exception ex) {
            log.error("Redis getControl failed taskId={}", taskId, ex);
            return CONTROL_RUNNING;
        }
    }

    /**
     * 发布事件 JSON 到任务频道。
     *
     * @return false 表示 Redis 不可用（调用方可降级）
     */
    public boolean publishEvent(String taskId, String json) {
        try {
            redisTemplate.convertAndSend(eventsChannel(taskId), json);
            return true;
        } catch (Exception ex) {
            log.error("Redis publishEvent failed taskId={}", taskId, ex);
            return false;
        }
    }
}
