package com.onelake.common.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.concurrent.TimeUnit;

/**
 * 共享 WebClient 配置：调用数据面组件（Airbyte/Dagster/OpenMetadata/APISIX）复用。
 * 各模块在 client 层用 WebClient.Builder().baseUrl(...).build()。
 */
@Configuration
public class WebClientConfig {

    @Value("${onelake.webclient.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${onelake.webclient.read-timeout-ms:15000}")
    private int readTimeoutMs;

    @Value("${onelake.webclient.max-in-memory-size-bytes:8388608}")
    private int maxInMemorySizeBytes;

    @Bean
    WebClient.Builder webClientBuilder() {
        HttpClient http = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
            .doOnConnected(c -> c
                .addHandlerLast(new ReadTimeoutHandler(readTimeoutMs, TimeUnit.MILLISECONDS))
                .addHandlerLast(new WriteTimeoutHandler(readTimeoutMs, TimeUnit.MILLISECONDS)));
        return WebClient.builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(maxInMemorySizeBytes))
            .clientConnector(new ReactorClientHttpConnector(http))
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
    }
}
