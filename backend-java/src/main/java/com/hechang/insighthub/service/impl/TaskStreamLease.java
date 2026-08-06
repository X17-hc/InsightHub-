package com.hechang.insighthub.service.impl;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * 单任务流式消费者互斥：pause/cancel/resume 时使旧 consumer 失效，避免双消费覆盖状态。
 */
@Component
public class TaskStreamLease {

    private final ConcurrentHashMap<String, AtomicLong> generations = new ConcurrentHashMap<>();

    /**
     * 开始新的流消费世代。
     *
     * @return 本 consumer 持有的 generation
     */
    public long acquire(String taskId) {
        return generations.computeIfAbsent(taskId, k -> new AtomicLong()).incrementAndGet();
    }

    /** 当前 generation 是否仍有效。 */
    public boolean isCurrent(String taskId, long generation) {
        AtomicLong cur = generations.get(taskId);
        return cur != null && cur.get() == generation;
    }

    /** 使进行中的 consumer 失效（pause/cancel）。 */
    public void invalidate(String taskId) {
        generations.computeIfAbsent(taskId, k -> new AtomicLong()).incrementAndGet();
    }

    /**
     * consumer 结束时清理；仅当仍是当前世代才移除，避免误清新流。
     */
    public void release(String taskId, long generation) {
        AtomicLong cur = generations.get(taskId);
        if (cur != null && cur.get() == generation) {
            generations.remove(taskId, cur);
        }
    }
}
