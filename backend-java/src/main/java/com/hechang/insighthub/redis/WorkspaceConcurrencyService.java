package com.hechang.insighthub.redis;

import org.redisson.api.RSemaphore;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.hechang.insighthub.exception.BusinessException;
import com.hechang.insighthub.mapper.WorkspaceMapper;

/**
 * 工作空间并发槽（Redisson Semaphore）。
 */
@Service
public class WorkspaceConcurrencyService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceConcurrencyService.class);

    private final RedissonClient redissonClient;
    private final WorkspaceMapper workspaceMapper;

    public WorkspaceConcurrencyService(RedissonClient redissonClient, WorkspaceMapper workspaceMapper) {
        this.redissonClient = redissonClient;
        this.workspaceMapper = workspaceMapper;
    }

    private static String key(String workspaceId) {
        return "ih:ws:" + workspaceId + ":slots";
    }

    /**
     * 尝试占用一个并发槽。
     *
     * @return true 表示已占用 Redis Semaphore 许可；false 表示 Redis 故障降级放行（未占许可，勿 markHeld）
     * @throws BusinessException 429 WORKSPACE_BUSY
     */
    public boolean tryAcquire(String workspaceId) {
        try {
            Integer max = workspaceMapper.selectMaxConcurrentTasks(workspaceId);
            int permits = max == null || max < 1 ? 1 : max;
            RSemaphore sem = redissonClient.getSemaphore(key(workspaceId));
            // 首次设置 permits；已存在则忽略
            sem.trySetPermits(permits);
            boolean ok = sem.tryAcquire();
            if (!ok) {
                throw BusinessException.tooManyRequests(
                        "WORKSPACE_BUSY",
                        "workspace concurrent task limit reached");
            }
            return true;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            // Redis 故障：开放并打错误日志；调用方不得 markHeld / release
            log.error("Workspace concurrency acquire degraded (allow) workspaceId={}", workspaceId, ex);
            return false;
        }
    }

    public void release(String workspaceId) {
        try {
            RSemaphore sem = redissonClient.getSemaphore(key(workspaceId));
            sem.release();
        } catch (Exception ex) {
            log.error("Workspace concurrency release failed workspaceId={}", workspaceId, ex);
        }
    }
}
