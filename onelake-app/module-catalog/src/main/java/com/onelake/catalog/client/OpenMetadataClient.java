package com.onelake.catalog.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.common.exception.DataplaneException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * OpenMetadata 客户端（对应《技术初始化文档》§6.12）。
 */
@Component
@RequiredArgsConstructor
public class OpenMetadataClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${onelake.dataplane.openmetadata.base-url:http://localhost:8585/api/v1}")
    private String baseUrl;

    @Value("${onelake.dataplane.openmetadata.token:}")
    private String token;

    public JsonNode listTables(int limit) {
        WebClient client = webClientBuilder.baseUrl(baseUrl).build();
        var req = client.get().uri("/tables?fields=owner,tags,columns&limit=" + limit);
        if (token != null && !token.isBlank()) {
            req = req.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return req.retrieve()
            .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                r -> r.bodyToMono(String.class).map(b -> new DataplaneException("openmetadata listTables failed: " + b)))
            .bodyToMono(JsonNode.class)
            .block();
    }
}
