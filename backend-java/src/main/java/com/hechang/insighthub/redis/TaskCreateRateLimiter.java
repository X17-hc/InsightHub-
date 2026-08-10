package com.hechang.insighthub.redis;

import java.time.Duration;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.hechang.insighthub.exception.BusinessException;
import com.hechang.insighthub.config.TaskProperties;

/**
 * 用户级创建任务限流。
 */
@Service
public class TaskCreateRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TaskCreateRateLimiter.class);

    private final RedissonClient redissonClient;
    private final TaskProperties taskProperties;

    public TaskCreateRateLimiter(RedissonClient redissonClient, TaskProperties taskProperties) {
        this.redissonClient = redissonClient;
        this.taskProperties = taskProperties;
    }

    /**
     * 获取创建许可；超限 429。
     */
    public void acquire(String userId) {
        try {
            int rate = Math.max(1, taskProperties.getCreateRatePerMinute());
            RRateLimiter limiter = redissonClient.getRateLimiter("ih:rl:user:" + userId + ":create-task");
            limiter.trySetRate(RateType.OVERALL, rate, 1, RateIntervalUnit.MINUTES);
            // 防止限流器永久占用内存：空闲后过期（尽力）
            limiter.expire(Duration.ofHours(2));
            if (!limiter.tryAcquire(1)) {
                throw BusinessException.tooManyRequests(
                        "RATE_LIMITED",
                        "too many task creations; retry later");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("RateLimiter unavailable userId={}", userId, ex);
            throw new BusinessException(
                    com.hechang.insighthub.exception.ErrorCode.OPERATION_ERROR,
                    "TASK_RATE_LIMITER_UNAVAILABLE: Redis is required for task admission");
        }
    }
}
