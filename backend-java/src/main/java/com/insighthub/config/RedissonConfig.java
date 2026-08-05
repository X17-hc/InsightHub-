package com.insighthub.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Redisson 客户端：无密码时不发送 AUTH（避免本机 Redis 报错）。
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    @Primary
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:127.0.0.1}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.database:0}") int database,
            @Value("${spring.data.redis.password:}") String password) {
        Config config = new Config();
        SingleServerConfig server = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database);
        if (password != null && !password.isBlank()) {
            server.setPassword(password);
        }
        return Redisson.create(config);
    }
}
