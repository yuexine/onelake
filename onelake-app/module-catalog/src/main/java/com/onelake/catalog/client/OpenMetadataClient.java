package com.onelake.catalog.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.common.exception.DataplaneException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenMetadata 客户端（对应《技术初始化文档》§6.12）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenMetadataClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${onelake.dataplane.openmetadata.base-url:http://localhost:8585/api/v1}")
    private String baseUrl;

    @Value("${onelake.dataplane.openmetadata.token:}")
    private String token;

    @Value("${onelake.dataplane.openmetadata.writeback-enabled:false}")
    private boolean writebackEnabled;

    public JsonNode listTables(int limit) {
        WebClient.RequestHeadersSpec<?> req = client().get().uri("/tables?fields=owner,tags,columns&limit=" + limit);
        req = withAuth(req);
        return req.retrieve()
            .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                r -> r.bodyToMono(String.class).map(b -> new DataplaneException("openmetadata listTables failed: " + b)))
            .bodyToMono(JsonNode.class)
            .block();
    }

    public void upsertIntegrationTable(String targetTable, String displayName, String description, JsonNode columns) {
        if (!writebackEnabled) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", displayName == null || displayName.isBlank() ? tableNameOf(targetTable) : displayName);
        payload.put("fullyQualifiedName", targetTable);
        payload.put("displayName", displayName);
        payload.put("description", description);
        payload.put("columns", toOpenMetadataColumns(columns));

        WebClient.RequestHeadersSpec<?> req = client().put().uri("/tables").bodyValue(payload);
        req = withAuth(req);
        req.retrieve()
            .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                r -> r.bodyToMono(String.class).map(b -> new DataplaneException("openmetadata upsert table failed: " + b)))
            .bodyToMono(Void.class)
            .block();
        log.info("openmetadata table writeback requested for {}", targetTable);
    }

    public void upsertIntegrationLineage(String sourceTable, String targetTable, String runId) {
        if (!writebackEnabled || sourceTable == null || sourceTable.isBlank() || targetTable == null || targetTable.isBlank()) return;
        Map<String, Object> payload = Map.of(
            "edge", Map.of(
                "fromEntity", Map.of("type", "table", "fullyQualifiedName", sourceTable),
                "toEntity", Map.of("type", "table", "fullyQualifiedName", targetTable),
                "lineageDetails", Map.of("source", "OneLake Integration", "pipeline", runId == null ? "" : runId)
            )
        );
        WebClient.RequestHeadersSpec<?> req = client().put().uri("/lineage").bodyValue(payload);
        req = withAuth(req);
        req.retrieve()
            .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                r -> r.bodyToMono(String.class).map(b -> new DataplaneException("openmetadata upsert lineage failed: " + b)))
            .bodyToMono(Void.class)
            .block();
        log.info("openmetadata lineage writeback requested for {} -> {}", sourceTable, targetTable);
    }

    private WebClient client() {
        return webClientBuilder.baseUrl(baseUrl).build();
    }

    private WebClient.RequestHeadersSpec<?> withAuth(WebClient.RequestHeadersSpec<?> req) {
        if (token != null && !token.isBlank()) {
            return req.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return req;
    }

    private List<Map<String, Object>> toOpenMetadataColumns(JsonNode columns) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (columns == null || !columns.isArray()) return result;
        for (JsonNode column : columns) {
            String name = column.path("name").asText("");
            if (name.isBlank()) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("dataType", column.path("type").asText("STRING"));
            String description = column.path("description").asText("");
            if (!description.isBlank()) item.put("description", description);
            result.add(item);
        }
        return result;
    }

    private String tableNameOf(String fqn) {
        if (fqn == null || fqn.isBlank()) return "-";
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 && dot < fqn.length() - 1 ? fqn.substring(dot + 1) : fqn;
    }
}
