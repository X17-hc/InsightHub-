package com.hechang.insighthub.service.impl;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 单任务流式消费者互斥：pause/cancel/resume 时使旧 consumer 失效，避免双消费覆盖状态。
 */
@Component
public class TaskStreamLease {

    private static final Logger log = LoggerFactory.getLogger(TaskStreamLease.class);
    private static final Duration LEASE_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;
    private final ConcurrentHashMap<String, String> localTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> localOnlyTokens = new ConcurrentHashMap<>();

    public TaskStreamLease(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    private static String key(String taskId) {
        return "ih:task:" + taskId + ":stream-generation";
    }

    /**
     * 开始新的流消费世代。
     *
     * @return 本 consumer 持有的跨实例 token
     */
    public String acquire(String taskId) {
        String token = UUID.randomUUID().toString();
        localTokens.put(taskId, token);
        try {
            redisTemplate.opsForValue().set(key(taskId), token, LEASE_TTL);
            localOnlyTokens.remove(taskId);
        } catch (Exception ex) {
            localOnlyTokens.put(taskId, token);
            log.error("stream lease acquire degraded to local taskId={}", taskId, ex);
        }
        return token;
    }

    /** 当前 token 是否仍有效。 */
    public boolean isCurrent(String taskId, String token) {
        try {
            String current = redisTemplate.opsForValue().get(key(taskId));
            if (current != null) {
                boolean matches = current.equals(token);
                if (matches) {
                    redisTemplate.expire(key(taskId), LEASE_TTL);
                }
                return matches;
            }
            return token.equals(localOnlyTokens.get(taskId));
        } catch (Exception ex) {
            log.error("stream lease read degraded to local taskId={}", taskId, ex);
            return token.equals(localTokens.get(taskId));
        }
    }

    /** 使所有进行中的 consumer 失效。 */
    public void invalidate(String taskId) {
        String tombstone = "invalid-" + UUID.randomUUID();
        localTokens.put(taskId, tombstone);
        try {
            redisTemplate.opsForValue().set(key(taskId), tombstone, LEASE_TTL);
            localOnlyTokens.remove(taskId);
        } catch (Exception ex) {
            localOnlyTokens.put(taskId, tombstone);
            log.error("stream lease invalidate degraded to local taskId={}", taskId, ex);
        }
    }

    /**
     * consumer 结束时清理；仅当仍是当前世代才移除，避免误清新流。
     */
    public void release(String taskId, String token) {
        localTokens.remove(taskId, token);
        localOnlyTokens.remove(taskId, token);
    }
}
