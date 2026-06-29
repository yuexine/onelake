package com.onelake.integration.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.common.exception.DataplaneException;
import com.onelake.common.util.JsonUtil;
import com.onelake.integration.dto.AirbyteConnectorDefinitionDTO;
import com.onelake.integration.dto.AirbyteConnectorSpecDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Airbyte 同步驱动（对应《技术初始化文档》§6.4 与《数据面开发指南》§3）。
 *
 * <p>能力清单：
 * <ul>
 *   <li>{@link #triggerSync} — 触发一次同步，返回 Airbyte job id</li>
 *   <li>{@link #getJobStatus} — 轮询 job 状态（succeeded / failed / running / pending / cancelled）</li>
 *   <li>{@link #cancel} — 取消运行中 job</li>
 *   <li>{@link #ensureSource} — 缺少 source id 时创建 Airbyte source</li>
 *   <li>{@link #ensureDestination} — 缺少 destination id 时创建 Airbyte destination</li>
 *   <li>{@link #ensureConnection} — 幂等：按 sourceId/destinationId 查找或创建 connection，返回 connectionId</li>
 * </ul>
 *
 * <p>当前实现没有显式 {@code @Retryable} / {@code @CircuitBreaker} —— Airbyte REST 在
 * 5xx 时直接抛 {@link DataplaneException}，调用方（Service）需自行决定是否重试或转人工。
 * 接入 resilience4j 后此处可加注解。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AirbyteSyncDriver {

    private final WebClient.Builder webClientBuilder;

    @Value("${onelake.dataplane.airbyte.base-url:http://localhost:8000/api/v1}")
    private String baseUrl;

    @Value("${onelake.dataplane.airbyte.workspace-id:}")
    private String workspaceId;

    @Value("${onelake.dataplane.airbyte.auth.client-id:}")
    private String clientId;

    @Value("${onelake.dataplane.airbyte.auth.client-secret:}")
    private String clientSecret;

    @Value("${onelake.dataplane.airbyte.auth.token-path:/applications/token}")
    private String tokenPath;

    @Value("${onelake.dataplane.airbyte.discover-schema-on-publish:false}")
    private boolean discoverSchemaOnPublish;

    private volatile String accessToken;
    private volatile Instant accessTokenExpiresAt = Instant.EPOCH;

    private WebClient client() {
        return webClientBuilder.baseUrl(baseUrl).build();
    }

    /** 触发一次同步，返回 Airbyte job id；控制面据此回写 sync_run。 */
    public long triggerSync(String connectionId) {
        log.info("airbyte triggerSync connectionId={}", connectionId);
        JsonNode resp = post("/connections/sync", Map.of("connectionId", connectionId));
        if (resp == null) throw new DataplaneException("airbyte empty response on triggerSync");
        return resp.path("job").path("id").asLong();
    }

    /** 轮询 job 状态，供 orchestration 模块聚合展示。 */
    public String getJobStatus(long jobId) {
        return getJobSnapshot(jobId).status();
    }

    /** 读取 Airbyte job 快照，供本地 sync_run reconcile 回写状态和统计。 */
    public AirbyteJobSnapshot getJobSnapshot(long jobId) {
        JsonNode resp = post("/jobs/get", Map.of("id", jobId));
        if (resp == null) {
            return new AirbyteJobSnapshot("unknown", null, null, null);
        }
        JsonNode job = resp.path("job");
        JsonNode attempts = resp.path("attempts");
        Long recordsSynced = null;
        Long bytesSynced = null;
        String errorMessage = null;
        if (attempts.isArray() && attempts.size() > 0) {
            JsonNode lastAttempt = attempts.get(attempts.size() - 1);
            JsonNode attemptStats = lastAttempt.has("attempt") ? lastAttempt.path("attempt") : lastAttempt;
            recordsSynced = nullableLong(attemptStats.path("recordsSynced"));
            bytesSynced = nullableLong(attemptStats.path("bytesSynced"));
            errorMessage = firstText(
                lastAttempt.at("/failureSummary/failures/0/externalMessage"),
                lastAttempt.at("/failureSummary/failures/0/internalMessage"),
                attemptStats.at("/failureSummary/failures/0/externalMessage"),
                attemptStats.at("/failureSummary/failures/0/internalMessage"),
                lastAttempt.path("failureSummary").path("partialSuccessMessage")
            );
        }
        if (errorMessage == null || errorMessage.isBlank()) {
            errorMessage = firstText(job.path("error"), job.path("failureReason"));
        }
        return new AirbyteJobSnapshot(
            job.path("status").asText("unknown"),
            recordsSynced,
            bytesSynced,
            errorMessage
        );
    }

    /**
     * 取消运行中 job。返回是否成功（job 已结束或不存在也算成功）。
     * 用于 sync_run 失败处置、用户主动停止等场景。
     */
    public boolean cancel(long jobId) {
        log.info("airbyte cancel jobId={}", jobId);
        try {
            JsonNode resp = post("/jobs/cancel", Map.of("id", jobId));
            if (resp == null) {
                log.warn("airbyte cancel empty response, treating as success");
                return true;
            }
            String status = resp.path("job").path("status").asText("");
            return "cancelled".equalsIgnoreCase(status);
        } catch (DataplaneException e) {
            log.warn("airbyte cancel failed for jobId={} (may already be finished): {}", jobId, e.getMessage());
            return false;
        }
    }

    /**
     * 幂等准备 source：已有 id 直接复用；否则按 connector definition 和配置创建。
     */
    public String ensureSource(String sourceId,
                               String workspaceId,
                               String sourceDefinitionId,
                               String name,
                               Map<String, Object> connectionConfiguration) {
        if (sourceId != null && !sourceId.isBlank()) {
            updateSourceBestEffort(sourceId, name, connectionConfiguration);
            return sourceId;
        }
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new DataplaneException("airbyte workspaceId is required to create source");
        }
        if (sourceDefinitionId == null || sourceDefinitionId.isBlank()) {
            throw new DataplaneException("airbyte sourceDefinitionId is required to create source");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspaceId", workspaceId);
        body.put("sourceDefinitionId", sourceDefinitionId);
        body.put("name", name);
        body.put("connectionConfiguration", connectionConfiguration == null ? Map.of() : connectionConfiguration);
        JsonNode created = post("/sources/create", body);
        String createdId = created == null ? "" : created.path("sourceId").asText("");
        if (createdId.isBlank()) {
            throw new DataplaneException("airbyte createSource returned no sourceId");
        }
        return createdId;
    }

    private void updateSourceBestEffort(String sourceId,
                                        String name,
                                        Map<String, Object> connectionConfiguration) {
        if (!StringUtils.hasText(sourceId) || connectionConfiguration == null || connectionConfiguration.isEmpty()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceId", sourceId);
        if (StringUtils.hasText(name)) {
            body.put("name", name);
        }
        body.put("connectionConfiguration", connectionConfiguration);
        try {
            post("/sources/update", body);
        } catch (DataplaneException e) {
            log.warn("airbyte updateSource skipped for sourceId={}: {}", sourceId, e.getMessage());
        }
    }

    /**
     * 幂等准备 destination：已有 id 直接复用；否则按 connector definition 和配置创建。
     */
    public String ensureDestination(String destinationId,
                                    String workspaceId,
                                    String destinationDefinitionId,
                                    String name,
                                    Map<String, Object> connectionConfiguration) {
        if (destinationId != null && !destinationId.isBlank()) {
            return destinationId;
        }
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new DataplaneException("airbyte workspaceId is required to create destination");
        }
        if (destinationDefinitionId == null || destinationDefinitionId.isBlank()) {
            throw new DataplaneException("airbyte destinationDefinitionId is required to create destination");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspaceId", workspaceId);
        body.put("destinationDefinitionId", destinationDefinitionId);
        body.put("name", name);
        body.put("connectionConfiguration", connectionConfiguration == null ? Map.of() : connectionConfiguration);
        JsonNode created = post("/destinations/create", body);
        String createdId = created == null ? "" : created.path("destinationId").asText("");
        if (createdId.isBlank()) {
            throw new DataplaneException("airbyte createDestination returned no destinationId");
        }
        return createdId;
    }

    /** 校验 connection 是否存在且 Airbyte API 可达。 */
    public boolean checkConnection(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return false;
        }
        JsonNode resp = post("/connections/get", Map.of("connectionId", connectionId));
        return resp != null && connectionId.equals(resp.path("connectionId").asText(connectionId));
    }

    /** 查询可用 source connector definition，用于数据源创建表单辅助配置。 */
    public List<AirbyteConnectorDefinitionDTO> listSourceDefinitions() {
        JsonNode resp = post("/source_definitions/list", Map.of());
        return connectorDefinitions(resp, "sourceDefinitions", "sourceDefinitionId", "SOURCE");
    }

    /** 查询可用 destination connector definition，用于后续 lakehouse 目的端配置。 */
    public List<AirbyteConnectorDefinitionDTO> listDestinationDefinitions() {
        JsonNode resp = post("/destination_definitions/list", Map.of());
        return connectorDefinitions(resp, "destinationDefinitions", "destinationDefinitionId", "DESTINATION");
    }

    /** 读取 source connector 的 JSON Schema 配置规格。 */
    public AirbyteConnectorSpecDTO getSourceDefinitionSpec(String definitionId) {
        JsonNode resp = post("/source_definition_specifications/get",
            definitionSpecBody("sourceDefinitionId", definitionId));
        return connectorSpec(definitionId, "SOURCE", resp);
    }

    /** 读取 destination connector 的 JSON Schema 配置规格。 */
    public AirbyteConnectorSpecDTO getDestinationDefinitionSpec(String definitionId) {
        JsonNode resp = post("/destination_definition_specifications/get",
            definitionSpecBody("destinationDefinitionId", definitionId));
        return connectorSpec(definitionId, "DESTINATION", resp);
    }

    /**
     * 幂等：按 sourceId/destinationId 查找已有 connection；不存在则创建。
     * 返回 connectionId（uuid 形式字符串）。
     *
     * <p>注：Airbyte OSS API 没有按 (sourceId, destinationId) 索引的查询接口，
     * 当前实现使用 list + filter；规模较大时建议改为 Octopia GitOps 模式。
     */
    public String ensureConnection(String sourceId, String destinationId, String name) {
        return ensureConnection(sourceId, destinationId, name, null, null, List.of());
    }

    public String ensureConnection(String sourceId,
                                   String destinationId,
                                   String name,
                                   String sourceTable,
                                   String targetTable,
                                   List<Map<String, Object>> fieldMapping) {
        String requestedName = name == null || name.isBlank()
            ? "onelake-" + sourceId.substring(0, Math.min(8, sourceId.length()))
            : name;
        // 1. 查找已有
        JsonNode listResp = post("/connections/list", workspaceScopedBody());
        if (listResp != null && listResp.has("connections")) {
            for (JsonNode c : listResp.path("connections")) {
                String sId = c.path("sourceId").asText("");
                String dId = c.path("destinationId").asText("");
                String existingName = c.path("name").asText("");
                if (sourceId.equals(sId) && destinationId.equals(dId) && requestedName.equals(existingName)) {
                    String existing = c.path("connectionId").asText("");
                    log.info("airbyte ensureConnection reuse existing connectionId={}", existing);
                    return existing;
                }
            }
        }
        // 2. 创建
        log.info("airbyte ensureConnection creating new for source={} destination={}", sourceId, destinationId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceId", sourceId);
        body.put("destinationId", destinationId);
        body.put("name", requestedName);
        body.put("scheduleType", "manual");
        body.put("status", "active");
        Map<String, Object> syncCatalog = syncCatalog(sourceId, sourceTable, targetTable, fieldMapping);
        if (!syncCatalog.isEmpty()) {
            body.put("syncCatalog", syncCatalog);
        }
        String targetNamespace = namespaceOf(targetTable);
        if (StringUtils.hasText(targetNamespace)) {
            body.put("namespaceDefinition", "customformat");
            body.put("namespaceFormat", targetNamespace);
        }
        JsonNode created = post("/connections/create", body);
        if (created == null) throw new DataplaneException("airbyte createConnection empty response");
        String connectionId = created.path("connectionId").asText(null);
        if (connectionId == null || connectionId.isBlank()) {
            throw new DataplaneException("airbyte createConnection returned no connectionId");
        }
        return connectionId;
    }

    /** 从 Airbyte job 响应中提取尽可能多的日志行。 */
    public List<String> getJobLogs(long jobId) {
        JsonNode resp = post("/jobs/get", Map.of("id", jobId));
        if (resp == null) {
            return List.of("Airbyte returned an empty job response.");
        }
        List<String> lines = new ArrayList<>();
        JsonNode attempts = resp.path("attempts");
        if (attempts.isArray()) {
            for (JsonNode attempt : attempts) {
                collectLogLines(attempt.path("logs").path("logLines"), lines);
                collectLogLines(attempt.path("logs").path("events"), lines);
                String failure = firstText(
                    attempt.at("/failureSummary/failures/0/externalMessage"),
                    attempt.at("/failureSummary/failures/0/internalMessage")
                );
                if (failure != null && !failure.isBlank()) {
                    lines.add(failure);
                }
            }
        }
        if (lines.isEmpty()) {
            String status = resp.path("job").path("status").asText("unknown");
            lines.add("Airbyte job " + jobId + " status: " + status);
        }
        return lines;
    }

    /* ---------------- private helpers ---------------- */

    private void collectLogLines(JsonNode node, List<String> lines) {
        if (!node.isArray()) {
            return;
        }
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                lines.add(item.asText());
            } else if (item.has("message") && !item.path("message").asText("").isBlank()) {
                lines.add(item.path("message").asText());
            }
        }
    }

    private Long nullableLong(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asLong();
    }

    private String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isMissingNode() && !node.isNull() && !node.asText("").isBlank()) {
                return node.asText();
            }
        }
        return null;
    }

    private List<AirbyteConnectorDefinitionDTO> connectorDefinitions(JsonNode resp,
                                                                     String arrayField,
                                                                     String idField,
                                                                     String type) {
        if (resp == null || !resp.path(arrayField).isArray()) {
            return List.of();
        }
        List<AirbyteConnectorDefinitionDTO> definitions = new ArrayList<>();
        for (JsonNode item : resp.path(arrayField)) {
            definitions.add(new AirbyteConnectorDefinitionDTO(
                item.path(idField).asText(""),
                item.path("name").asText(""),
                item.path("dockerRepository").asText(""),
                item.path("dockerImageTag").asText(""),
                type
            ));
        }
        return definitions;
    }

    private AirbyteConnectorSpecDTO connectorSpec(String definitionId, String type, JsonNode resp) {
        if (resp == null) {
            return new AirbyteConnectorSpecDTO(definitionId, type, null, null);
        }
        return new AirbyteConnectorSpecDTO(
            definitionId,
            type,
            firstText(resp.path("documentationUrl"), resp.path("documentation_url")),
            resp.path("connectionSpecification")
        );
    }

    private Map<String, Object> definitionSpecBody(String idField, String definitionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(idField, definitionId);
        if (StringUtils.hasText(workspaceId)) {
            body.put("workspaceId", workspaceId);
        }
        return body;
    }

    private Map<String, Object> workspaceScopedBody() {
        if (!StringUtils.hasText(workspaceId)) {
            return Map.of();
        }
        return Map.of("workspaceId", workspaceId);
    }

    private Map<String, Object> syncCatalog(String sourceId,
                                            String sourceTable,
                                            String targetTable,
                                            List<Map<String, Object>> fieldMapping) {
        if (!StringUtils.hasText(sourceTable) || fieldMapping == null || fieldMapping.isEmpty()) {
            return Map.of();
        }
        if (discoverSchemaOnPublish) {
            Map<String, Object> discovered = discoveredSyncCatalog(sourceId, sourceTable, targetTable);
            if (!discovered.isEmpty()) {
                return discovered;
            }
        }
        String namespace = namespaceOf(sourceTable);
        String table = tableNameOf(sourceTable);
        Map<String, Object> stream = new LinkedHashMap<>();
        stream.put("name", table);
        if (StringUtils.hasText(namespace)) {
            stream.put("namespace", namespace);
        }
        stream.put("jsonSchema", jsonSchema(fieldMapping));
        stream.put("supportedSyncModes", List.of("full_refresh"));
        stream.put("sourceDefinedCursor", false);
        List<List<String>> primaryKey = inferredPrimaryKey(fieldMapping);
        stream.put("sourceDefinedPrimaryKey", primaryKey);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("syncMode", "full_refresh");
        config.put("destinationSyncMode", "overwrite");
        config.put("selected", true);
        config.put("aliasName", StringUtils.hasText(targetTable) ? tableNameOf(targetTable) : table);
        config.put("primaryKey", primaryKey);
        return Map.of("streams", List.of(Map.of("stream", stream, "config", config)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> discoveredSyncCatalog(String sourceId, String sourceTable, String targetTable) {
        if (!StringUtils.hasText(sourceId)) {
            return Map.of();
        }
        try {
            JsonNode resp = post("/sources/discover_schema", Map.of("sourceId", sourceId, "disable_cache", true));
            JsonNode streams = resp == null ? null : resp.path("catalog").path("streams");
            if (streams == null || !streams.isArray()) {
                return Map.of();
            }
            String sourceNamespace = namespaceOf(sourceTable);
            String sourceName = tableNameOf(sourceTable);
            for (JsonNode item : streams) {
                JsonNode streamNode = item.path("stream");
                String name = streamNode.path("name").asText("");
                String namespace = streamNode.path("namespace").asText("");
                if (!sourceName.equals(name) || (StringUtils.hasText(sourceNamespace) && !sourceNamespace.equals(namespace))) {
                    continue;
                }
                Map<String, Object> stream = JsonUtil.mapper().convertValue(streamNode, LinkedHashMap.class);
                Map<String, Object> config = JsonUtil.mapper().convertValue(item.path("config"), LinkedHashMap.class);
                config.put("selected", true);
                config.put("syncMode", "full_refresh");
                config.put("destinationSyncMode", "overwrite");
                if (StringUtils.hasText(targetTable)) {
                    config.put("aliasName", tableNameOf(targetTable));
                }
                return Map.of("streams", List.of(Map.of("stream", stream, "config", config)));
            }
        } catch (RuntimeException e) {
            log.warn("airbyte discover schema failed for sourceId={}, falling back to field mapping catalog: {}", sourceId, e.getMessage());
        }
        return Map.of();
    }

    private List<List<String>> inferredPrimaryKey(List<Map<String, Object>> fieldMapping) {
        for (Map<String, Object> field : fieldMapping) {
            Object name = field.get("source");
            if (name != null && "id".equalsIgnoreCase(String.valueOf(name))) {
                return List.of(List.of(String.valueOf(name)));
            }
        }
        return List.of();
    }

    private Map<String, Object> jsonSchema(List<Map<String, Object>> fieldMapping) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map<String, Object> field : fieldMapping) {
            Object name = field.get("source");
            if (name == null || !StringUtils.hasText(String.valueOf(name))) {
                continue;
            }
            Object type = field.getOrDefault("sourceType", field.get("targetType"));
            properties.put(String.valueOf(name), jsonSchemaType(type == null ? "" : String.valueOf(type)));
        }
        return Map.of("type", "object", "properties", properties);
    }

    private Map<String, Object> jsonSchemaType(String type) {
        String normalized = type == null ? "" : type.toUpperCase();
        if (normalized.contains("INT")) {
            return Map.of("type", "number", "airbyte_type", "integer");
        }
        if (normalized.contains("DECIMAL") || normalized.contains("NUMERIC")
            || normalized.contains("DOUBLE") || normalized.contains("FLOAT") || normalized.contains("REAL")) {
            return Map.of("type", "number");
        }
        if (normalized.contains("BOOL")) {
            return Map.of("type", "boolean");
        }
        if (normalized.contains("TIME") || normalized.contains("DATE")) {
            return Map.of("type", "string", "format", "date-time", "airbyte_type", "timestamp_without_timezone");
        }
        return Map.of("type", "string");
    }

    private String namespaceOf(String tableName) {
        int dot = tableName == null ? -1 : tableName.lastIndexOf('.');
        return dot > 0 ? tableName.substring(0, dot) : "";
    }

    private String tableNameOf(String tableName) {
        if (!StringUtils.hasText(tableName)) {
            return "";
        }
        int dot = tableName.lastIndexOf('.');
        return dot >= 0 ? tableName.substring(dot + 1) : tableName;
    }

    private JsonNode post(String uri, Object body) {
        try {
            WebClient.RequestBodySpec request = client().post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON);
            String token = currentAccessToken();
            if (StringUtils.hasText(token)) {
                request.headers(headers -> headers.setBearerAuth(token));
            }
            return request
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                            r -> r.bodyToMono(String.class)
                                    .map(b -> new DataplaneException("airbyte " + uri + " failed: " + b)))
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (DataplaneException e) {
            throw e;
        } catch (Exception e) {
            throw new DataplaneException("airbyte " + uri + " unreachable: " + e.getMessage());
        }
    }

    private String currentAccessToken() {
        if (!authConfigured()) {
            return "";
        }
        Instant now = Instant.now();
        if (StringUtils.hasText(accessToken) && now.isBefore(accessTokenExpiresAt.minusSeconds(30))) {
            return accessToken;
        }
        synchronized (this) {
            now = Instant.now();
            if (StringUtils.hasText(accessToken) && now.isBefore(accessTokenExpiresAt.minusSeconds(30))) {
                return accessToken;
            }
            JsonNode resp = fetchAccessToken();
            String token = resp.path("access_token").asText("");
            if (!StringUtils.hasText(token)) {
                throw new DataplaneException("airbyte token endpoint returned no access_token");
            }
            long expiresIn = Math.max(60, resp.path("expires_in").asLong(900));
            accessToken = token;
            accessTokenExpiresAt = now.plusSeconds(expiresIn);
            return accessToken;
        }
    }

    private boolean authConfigured() {
        return StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
    }

    private JsonNode fetchAccessToken() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_id", clientId);
        body.put("client_secret", clientSecret);
        body.put("grant_type", "client_credentials");
        try {
            return client().post()
                .uri(tokenPath)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                    r -> r.bodyToMono(String.class)
                        .map(b -> new DataplaneException("airbyte token request failed: " + b)))
                .bodyToMono(JsonNode.class)
                .block();
        } catch (DataplaneException e) {
            throw e;
        } catch (Exception e) {
            throw new DataplaneException("airbyte token endpoint unreachable: " + e.getMessage());
        }
    }

    public record AirbyteJobSnapshot(
        String status,
        Long recordsSynced,
        Long bytesSynced,
        String errorMessage
    ) {
        public Map<String, Object> checkpoint(long jobId) {
            Map<String, Object> checkpoint = new LinkedHashMap<>();
            checkpoint.put("driver", "AIRBYTE");
            checkpoint.put("jobId", jobId);
            checkpoint.put("status", status);
            checkpoint.put("syncedAt", Instant.now().toString());
            if (bytesSynced != null) checkpoint.put("bytesSynced", bytesSynced);
            return checkpoint;
        }
    }
}
