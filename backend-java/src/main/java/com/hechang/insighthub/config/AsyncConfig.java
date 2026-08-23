package com.hechang.insighthub.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Explicit bulkheads for work with materially different execution times.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "agentStreamExecutor")
    public Executor agentStreamExecutor() {
        return executor("ih-agent-stream-", 4, 16, 32);
    }

    @Bean(name = "knowledgeIngestExecutor")
    public Executor knowledgeIngestExecutor() {
        return executor("ih-knowledge-ingest-", 2, 4, 64);
    }

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService sseScheduler() {
        return Executors.newScheduledThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "ih-sse-");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static ThreadPoolTaskExecutor executor(String threadNamePrefix, int coreSize, int maxSize, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
