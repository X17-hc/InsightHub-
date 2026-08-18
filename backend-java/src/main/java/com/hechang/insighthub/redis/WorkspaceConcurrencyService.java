package com.hechang.insighthub.redis;

import java.util.concurrent.TimeUnit;

import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.hechang.insighthub.exception.BusinessException;
import com.hechang.insighthub.exception.ErrorCode;
import com.hechang.insighthub.mapper.WorkspaceMapper;
import com.hechang.insighthub.model.entity.Workspace;

import lombok.RequiredArgsConstructor;

/**
 * 工作空间并发槽（Redisson Semaphore）。
 */
@Service
@RequiredArgsConstructor
public class WorkspaceConcurrencyService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceConcurrencyService.class);

    private final RedissonClient redissonClient;
    private final WorkspaceMapper workspaceMapper;

    private static String key(String workspaceId) {
        return "ih:ws:" + workspaceId + ":slots";
    }

    /**
     * 尝试占用一个并发槽。
     *
     * @return permit ID 表示已占用许可；null 表示 Redis 故障降级放行（未占许可，勿 markHeld）
     * @throws BusinessException 429 WORKSPACE_BUSY
     */
    public String tryAcquire(String workspaceId, int leaseSeconds) {
        try {
            Workspace workspace = workspaceMapper.selectOneById(workspaceId);
            Integer max = workspace == null ? null : workspace.getMaxConcurrentTasks();
            int permits = max == null || max < 1 ? 1 : max;
            RPermitExpirableSemaphore sem = redissonClient.getPermitExpirableSemaphore(key(workspaceId));
            // 首次设置 permits；已存在则忽略
            sem.trySetPermits(permits);
            String permitId = sem.tryAcquire(0, Math.max(60, leaseSeconds), TimeUnit.SECONDS);
            if (permitId == null) {
                throw BusinessException.tooManyRequests(
                        "WORKSPACE_BUSY",
                        "workspace concurrent task limit reached");
            }
            return permitId;
        } catch (BusinessException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.error("Workspace concurrency acquire interrupted workspaceId={}", workspaceId, ex);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "workspace concurrency acquire interrupted");
        } catch (Exception ex) {
            log.error("Workspace concurrency unavailable workspaceId={}", workspaceId, ex);
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "WORKSPACE_CONCURRENCY_UNAVAILABLE: Redis is required for task admission");
        }
    }

    public void release(String workspaceId, String permitId) {
        if (permitId == null || permitId.isBlank()) {
            return;
        }
        try {
            RPermitExpirableSemaphore sem = redissonClient.getPermitExpirableSemaphore(key(workspaceId));
            sem.release(permitId);
        } catch (Exception ex) {
            // permit 可能已按租期自动释放；不再额外增加许可
            log.warn("Workspace concurrency release skipped workspaceId={} permitId={}", workspaceId, permitId, ex);
        }
    }
}
