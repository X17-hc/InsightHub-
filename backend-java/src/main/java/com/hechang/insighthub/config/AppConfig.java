package com.hechang.insighthub.config;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.netty.http.client.HttpClient;

/**
 * WebClient 与配置属性装配。
 */
@Configuration
@EnableConfigurationProperties({
        AgentProperties.class,
        DemoProperties.class,
        JwtProperties.class,
        DocsProperties.class,
        TaskProperties.class,
        UploadProperties.class
})
public class AppConfig {

    /**
     * 调用 Python Agent 的 WebClient。
     *
     * @param props Agent 连接配置
     * @return 已设置 baseUrl 与超时的 WebClient
     */
    @Bean
    public WebClient agentWebClient(AgentProperties props) {
        if (props.getInternalToken() == null || props.getInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "insighthub.agent.internal-token must be configured; set AGENT_INTERNAL_TOKEN");
        }
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMillis(props.getReadTimeoutMs()))
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, props.getConnectTimeoutMs());
        return WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader("X-Internal-Token", props.getInternalToken())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /**
     * 短事务模板：任务终态 / 报告 / 事件落库。
     */
    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
