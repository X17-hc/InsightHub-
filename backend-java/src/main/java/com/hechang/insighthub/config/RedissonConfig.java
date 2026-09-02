package com.hechang.insighthub.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
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
    public RedissonClient redissonClient(RedisProperties properties) {
        Config config = new Config();
        SingleServerConfig server = config.useSingleServer()
                .setAddress("redis://" + properties.getHost() + ":" + properties.getPort())
                .setDatabase(properties.getDatabase());
        String username = properties.getUsername();
        if (username != null && !username.isBlank()) {
            server.setUsername(username);
        }
        String password = properties.getPassword();
        if (password != null && !password.isBlank()) {
            server.setPassword(password);
        }
        return Redisson.create(config);
    }
}
