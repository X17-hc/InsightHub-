package com.hechang.insighthub.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;

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
    public WebClient agentWebClient(AgentProperties props, ObjectMapper objectMapper) {
        if (props.getInternalToken() == null || props.getInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "insighthub.agent.internal-token must be configured; set AGENT_INTERNAL_TOKEN");
        }
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMillis(props.getReadTimeoutMs()))
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, props.getConnectTimeoutMs());
        // JSON 编解码固定 UTF-8，避免平台默认编码污染请求体中的中文 query
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> {
                    configurer.defaultCodecs().jackson2JsonEncoder(
                            new Jackson2JsonEncoder(objectMapper, MediaType.APPLICATION_JSON));
                    configurer.defaultCodecs().jackson2JsonDecoder(
                            new Jackson2JsonDecoder(objectMapper, MediaType.APPLICATION_JSON));
                })
                .build();
        return WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader("X-Internal-Token", props.getInternalToken())
                .defaultHeader("Accept-Charset", StandardCharsets.UTF_8.name())
                .defaultHeaders(headers -> headers.setAcceptCharset(
                        Collections.singletonList(StandardCharsets.UTF_8)))
                .exchangeStrategies(strategies)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /**
     * 保留无 ObjectMapper 参数的直接调用入口，兼容配置单元测试和本地工具调用。
     */
    public WebClient agentWebClient(AgentProperties props) {
        return agentWebClient(props, new ObjectMapper());
    }

    /**
     * 短事务模板：任务终态 / 报告 / 事件落库。
     */
    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
