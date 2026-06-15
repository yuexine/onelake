package com.onelake.integration.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.common.exception.DataplaneException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * Airbyte 同步驱动（对应《技术初始化文档》§6.4 与《数据面开发指南》§3）。
 *
 * <p>能力清单：
 * <ul>
 *   <li>{@link #triggerSync} — 触发一次同步，返回 Airbyte job id</li>
 *   <li>{@link #getJobStatus} — 轮询 job 状态（succeeded / failed / running / pending / cancelled）</li>
 *   <li>{@link #cancel} — 取消运行中 job</li>
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
        JsonNode resp = post("/jobs/get", Map.of("id", jobId));
        if (resp == null) return "unknown";
        return resp.path("job").path("status").asText("unknown");
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
     * 幂等：按 sourceId/destinationId 查找已有 connection；不存在则创建。
     * 返回 connectionId（uuid 形式字符串）。
     *
     * <p>注：Airbyte OSS API 没有按 (sourceId, destinationId) 索引的查询接口，
     * 当前实现使用 list + filter；规模较大时建议改为 Octopia GitOps 模式。
     */
    public String ensureConnection(String sourceId, String destinationId, String name) {
        // 1. 查找已有
        JsonNode listResp = post("/connections/list", Map.of());
        if (listResp != null && listResp.has("connections")) {
            for (JsonNode c : listResp.path("connections")) {
                String sId = c.path("sourceId").asText("");
                String dId = c.path("destinationId").asText("");
                if (sourceId.equals(sId) && destinationId.equals(dId)) {
                    String existing = c.path("connectionId").asText("");
                    log.info("airbyte ensureConnection reuse existing connectionId={}", existing);
                    return existing;
                }
            }
        }
        // 2. 创建
        log.info("airbyte ensureConnection creating new for source={} destination={}", sourceId, destinationId);
        Map<String, Object> body = Map.of(
                "sourceId", sourceId,
                "destinationId", destinationId,
                "name", name == null ? "onelake-" + sourceId.substring(0, Math.min(8, sourceId.length())) : name,
                "scheduleType", "manual",
                "status", "active"
        );
        JsonNode created = post("/connections/create", body);
        if (created == null) throw new DataplaneException("airbyte createConnection empty response");
        String connectionId = created.path("connectionId").asText(null);
        if (connectionId == null || connectionId.isBlank()) {
            throw new DataplaneException("airbyte createConnection returned no connectionId");
        }
        return connectionId;
    }

    /* ---------------- private helpers ---------------- */

    private JsonNode post(String uri, Object body) {
        try {
            return client().post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
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
}
