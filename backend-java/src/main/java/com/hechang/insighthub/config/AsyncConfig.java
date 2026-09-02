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
 * 为不同耗时模型的后台工作建立独立有界舱壁。
 *
 * <p>Agent 长流、知识入库和 SSE 定时任务不能共享默认无界线程池。队列满时使用
 * AbortPolicy 明确拒绝，由调用方记录/重试；不能在 Web 请求线程中静默执行，
 * 否则高负载会把背压传回请求链路。</p>
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
        // core/max/queue 共同给出硬上限；调整这些值必须结合数据库连接池、Agent
        // 并发额度和 Ubuntu Sandbox 资源，而不是只扩大线程数。
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
