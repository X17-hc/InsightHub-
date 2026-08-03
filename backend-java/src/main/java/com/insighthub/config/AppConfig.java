package com.insighthub.config;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.netty.http.client.HttpClient;

/**
 * WebClient 与配置属性装配。
 */
@Configuration
@EnableConfigurationProperties({AgentProperties.class, DemoProperties.class})
public class AppConfig {

    /**
     * 调用 Python Agent 的 WebClient。
     *
     * @param props Agent 连接配置
     * @return 已设置 baseUrl 与超时的 WebClient
     */
    @Bean
    public WebClient agentWebClient(AgentProperties props) {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMillis(props.getReadTimeoutMs()))
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, props.getConnectTimeoutMs());
        return WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
