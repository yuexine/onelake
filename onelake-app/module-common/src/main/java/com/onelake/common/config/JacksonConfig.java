package com.onelake.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onelake.common.util.JsonUtil;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 暴露 JsonUtil 内的 ObjectMapper 为 Spring Bean，
 * 让 WebClient / RestTemplate 复用同一份时间格式。
 */
@Configuration
public class JacksonConfig {

    @Bean
    ObjectMapper objectMapper() {
        return JsonUtil.mapper();
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder.timeZone("UTC");
    }
}
