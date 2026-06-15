package com.onelake.integration.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.common.exception.DataplaneException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * Airbyte 同步驱动（对应《技术初始化文档》§6.4）。
 * 触发一次同步，返回 Airbyte job id；轮询 job 状态供 orchestration 模块聚合。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AirbyteSyncDriver {

    private final WebClient.Builder webClientBuilder;

    @Value("${onelake.dataplane.airbyte.base-url:http://localhost:8000/api/v1}")
    private String baseUrl;

    private WebClient client() {
        return webClientBuilder.baseUrl(baseUrl).build();
    }

    /** 触发一次同步，返回 Airbyte job id；控制面据此回写 sync_run。 */
    public long triggerSync(String connectionId) {
        WebClient airbyte = client();
        JsonNode resp = airbyte.post().uri("/connections/sync")
            .bodyValue(Map.of("connectionId", connectionId))
            .retrieve()
            .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                r -> r.bodyToMono(String.class)
                    .map(b -> new DataplaneException("airbyte sync failed: " + b)))
            .bodyToMono(JsonNode.class)
            .block();
        if (resp == null) throw new DataplaneException("airbyte empty response");
        return resp.path("job").path("id").asLong();
    }

    /** 轮询 job 状态，供 orchestration 模块聚合展示。 */
    public String getJobStatus(long jobId) {
        WebClient airbyte = client();
        JsonNode resp = airbyte.post().uri("/jobs/get")
            .bodyValue(Map.of("id", jobId))
            .retrieve()
            .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                r -> r.bodyToMono(String.class)
                    .map(b -> new DataplaneException("airbyte getJobStatus failed: " + b)))
            .bodyToMono(JsonNode.class)
            .block();
        if (resp == null) return "unknown";
        return resp.path("job").path("status").asText("unknown");
    }
}
