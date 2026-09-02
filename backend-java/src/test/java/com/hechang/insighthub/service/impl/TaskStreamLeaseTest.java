package com.hechang.insighthub.service.impl;

import com.hechang.insighthub.service.task.TaskStreamLease;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class TaskStreamLeaseTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private TaskStreamLease lease;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lease = new TaskStreamLease(redisTemplate);
    }

    @Test
    void activeRedisLeaseIsRenewed() {
        String token = lease.acquire("task-1");
        when(valueOperations.get("ih:task:task-1:stream-generation")).thenReturn(token);

        assertTrue(lease.isCurrent("task-1", token));
        verify(redisTemplate).expire(
                "ih:task:task-1:stream-generation", Duration.ofMinutes(30));
    }

    @Test
    void missingRedisKeyDoesNotReviveRedisBackedToken() {
        String token = lease.acquire("task-2");
        when(valueOperations.get("ih:task:task-2:stream-generation")).thenReturn(null);

        assertFalse(lease.isCurrent("task-2", token));
    }

    @Test
    void redisFailureRejectsNewLease() {
        when(redisTemplate.opsForValue()).thenThrow(new IllegalStateException("redis down"));
        TaskStreamLease localLease = new TaskStreamLease(redisTemplate);

        assertThrows(IllegalStateException.class, () -> localLease.acquire("task-3"));
    }
}
