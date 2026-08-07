package com.hechang.insighthub.redis;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 跟踪任务是否占用工作空间并发槽。
 *
 * <p>Redis key {@code ih:task:{taskId}:slot} 为跨进程凭证；本地 map 防同 JVM 重复 release。
 * 仅在真正 acquire 成功后调用 {@link #markHeld}。
 */
@Component
public class TaskSlotTracker {

    private static final Logger log = LoggerFactory.getLogger(TaskSlotTracker.class);

    private final StringRedisTemplate redisTemplate;
    /** taskId -> 租约（本 JVM 缓存） */
    private final ConcurrentHashMap<String, SlotLease> localHeld = new ConcurrentHashMap<>();

    public TaskSlotTracker(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public static String slotKey(String taskId) {
        return "ih:task:" + taskId + ":slot";
    }

    /**
     * 记录任务已占用 Redis Semaphore 许可。
     *
     * @param taskId       任务 ID
     * @param workspaceId  工作空间
     * @param ttlSeconds   凭证 TTL（建议 timeout+600）
     */
    public void markHeld(String taskId, String workspaceId, String permitId, int ttlSeconds) {
        SlotLease lease = new SlotLease(workspaceId, permitId);
        localHeld.put(taskId, lease);
        try {
            redisTemplate.opsForValue().set(
                    slotKey(taskId),
                    lease.serialize(),
                    Duration.ofSeconds(Math.max(60, ttlSeconds)));
        } catch (Exception ex) {
            // 本 JVM 仍可 release；跨进程依赖 Redis 写成功
            log.error("Redis markHeld failed taskId={} (local tracker kept)", taskId, ex);
        }
    }

    /**
     * 若仍占用则清除凭证并执行 releaseAction（通常为 semaphore.release）。
     *
     * @return true 表示本次确实释放了
     */
    public boolean releaseOnce(String taskId, String workspaceId, Consumer<String> releaseAction) {
        SlotLease lease = localHeld.remove(taskId);
        try {
            String stored = redisTemplate.opsForValue().getAndDelete(slotKey(taskId));
            if (stored != null) {
                lease = SlotLease.parse(stored);
            }
        } catch (Exception ex) {
            log.error("Redis slot delete failed taskId={}", taskId, ex);
        }
        if (lease != null && (workspaceId == null || workspaceId.isBlank()
                || workspaceId.equals(lease.workspaceId()))) {
            releaseAction.accept(lease.permitId());
            return true;
        }
        return false;
    }

    /**
     * 兼容旧调用：无 workspace 时仅依赖本地 + Redis DELETE。
     */
    public boolean releaseOnce(String taskId, Consumer<String> releaseAction) {
        return releaseOnce(taskId, "", releaseAction);
    }

    private record SlotLease(String workspaceId, String permitId) {

        String serialize() {
            return workspaceId + "\n" + permitId;
        }

        static SlotLease parse(String raw) {
            int split = raw == null ? -1 : raw.indexOf('\n');
            if (split <= 0 || split >= raw.length() - 1) {
                return null;
            }
            return new SlotLease(raw.substring(0, split), raw.substring(split + 1));
        }
    }
}
