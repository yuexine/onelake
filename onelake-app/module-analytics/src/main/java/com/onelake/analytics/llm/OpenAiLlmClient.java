package com.onelake.analytics.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions 客户端（NL2SQL 默认提供商）。
 *
 * 配置：
 *   onelake.analytics.llm.provider=openai  （默认）
 *   onelake.analytics.llm.openai.api-key=$OPENAI_API_KEY
 *   onelake.analytics.llm.openai.base-url=https://api.openai.com/v1  （可换 Azure/代理）
 *   onelake.analytics.llm.openai.model=gpt-4o-mini
 *
 * 不可用（key 未配置）时 isAvailable()=false，NL2SQLService 拒绝调用并返回友好提示。
 */
@Slf4j
@Component("openAiLlmClient")
@ConditionalOnProperty(prefix = "onelake.analytics.llm", name = "provider", havingValue = "openai", matchIfMissing = true)
public class OpenAiLlmClient implements LlmClient {

    private final WebClient webClient;
    private final String apiKey;
    private final String model;

    public OpenAiLlmClient(
            @Value("${onelake.analytics.llm.openai.api-key:${OPENAI_API_KEY:}}") String apiKey,
            @Value("${onelake.analytics.llm.openai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${onelake.analytics.llm.openai.model:gpt-4o-mini}") String model,
            org.springframework.web.reactive.function.client.WebClient.Builder builder) {
        this.webClient = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String chatCompletion(String systemPrompt, String userPrompt) {
        if (!isAvailable()) {
            throw new BizException(50020,
                "OpenAI API key 未配置。请设置环境变量 OPENAI_API_KEY 或 onelake.analytics.llm.openai.api-key。");
        }
        Map<String, Object> body = Map.of(
            "model", model,
            "messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
            ),
            "temperature", 0.1,  // SQL 生成低 temperature 提高一致性
            "max_tokens", 1500
        );
        try {
            JsonNode resp = webClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(30))
                .block();
            JsonNode content = resp != null
                ? resp.path("choices").path(0).path("message").path("content")
                : null;
            if (content == null || content.isMissingNode() || content.isNull()) {
                throw new BizException(50020, "OpenAI 返回空内容");
            }
            return content.asText();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("OpenAI chatCompletion failed: {}", e.getMessage());
            throw new BizException(50020, "OpenAI 调用失败：" + e.getMessage(), e);
        }
    }
}
