package com.onelake.analytics.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.onelake.analytics.domain.entity.Dataset;
import com.onelake.analytics.domain.entity.QueryLog;
import com.onelake.analytics.dto.DataBinding;
import com.onelake.analytics.dto.DatasetDTO;
import com.onelake.analytics.dto.QueryResult;
import com.onelake.analytics.repository.QueryLogRepository;
import com.onelake.analytics.client.TrinoQueryClient;
import com.onelake.analytics.client.DataServiceClient;
import com.onelake.common.context.TenantContext;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 数据集查询服务（§7.6 v1.1 实现）。
 *
 * 关键设计：
 * 1. source_type=API：走 dataservice PostgREST，不入 Trino 缓存
 * 2. source_type=ASSET/SQL：脱敏完全下推到 Trino（SqlBuilder 注入 mask 表达式）
 * 3. Redis single-flight：同 SQL 多组件并发查询时只发一次 Trino 请求
 * 4. 异步写 query_log（不影响主路径）+ 慢查询告警（Outbox）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetQueryService {

    private final DatasetService datasetService;
    private final TrinoQueryClient trino;
    private final DataServiceClient dataserviceClient;
    private final StringRedisTemplate redis;
    private final MaskingPolicyView maskingView;
    private final SqlBuilder sqlBuilder;
    private final QueryLogRepository queryLogRepo;
    private final OutboxPublisher outbox;

    @Value("${onelake.dataplane.analytics.query.slow-threshold-ms:5000}")
    private long slowThresholdMs;

    /**
     * 本地 single-flight 锁桶（Redis SETNX 的进程内补充，降低 Redis 调用频次）。
     * key = cacheKey, value = ReentrantLock
     */
    private final Map<String, Lock> localLocks = new java.util.concurrent.ConcurrentHashMap<>();

    public QueryResult query(UUID datasetId, DataBinding binding, UUID dashboardId) {
        UUID tenant = TenantContext.getTenantId();
        Dataset ds = datasetService.getEntity(datasetId, tenant);
        long start = System.currentTimeMillis();

        String sqlFingerprint;
        QueryResult result;

        if (ds.getSourceType() == com.onelake.analytics.domain.enums.SourceType.API) {
            // ① API：反向调 dataservice PostgREST
            result = dataserviceClient.query(ds.getApiId(), binding);
            sqlFingerprint = "[api:" + ds.getApiId() + "]";
            result.setCacheHit(false);
        } else {
            // ② ASSET/SQL：脱敏下推到 Trino + single-flight 缓存
            Map<String, String> masking = maskingView.forTenant(tenant);
            String sql = sqlBuilder.compose(ds, binding, masking);
            sqlFingerprint = sql;
            String cacheKey = "ds:" + tenant + ":" + datasetId + ":" + md5(sql);
            result = singleFlight(cacheKey, Duration.ofSeconds(ds.getCacheTtlSec()), ds, sql);
        }

        // ③ 异步写查询日志 + 慢查询告警
        long durationMs = System.currentTimeMillis() - start;
        result.setDurationMs(durationMs);
        logQuery(datasetId, dashboardId, tenant, sqlFingerprint, durationMs,
                 result.getRows() == null ? 0 : result.getRows().size(), result.isCacheHit());
        if (durationMs > slowThresholdMs) {
            outbox.publish(DomainEvents.ANALYTICS_QUERY_SLOW, datasetId.toString(),
                Map.of("durationMs", durationMs, "tenantId", String.valueOf(tenant),
                       "dashboardId", String.valueOf(dashboardId)));
        }
        return result;
    }

    /**
     * Single-flight 缓存：3 个组件绑同一数据集时只发一次 Trino 查询。
     * 进程内 ReentrantLock + Redis 缓存 + 缓存命中直接返回。
     */
    private QueryResult singleFlight(String cacheKey, Duration ttl, Dataset ds, String sql) {
        // 1) 命中缓存
        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null) {
            QueryResult hit = JsonUtil.fromJson(cached, QueryResult.class);
            hit.setCacheHit(true);
            return hit;
        }

        // 2) single-flight 锁
        Lock lock = localLocks.computeIfAbsent(cacheKey, k -> new ReentrantLock());
        lock.lock();
        try {
            // double-check
            cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                QueryResult hit = JsonUtil.fromJson(cached, QueryResult.class);
                hit.setCacheHit(true);
                return hit;
            }
            // 执行 Trino 查询
            List<Map<String, Object>> rows = trino.query(sql);
            List<DatasetDTO.FieldSchema> fields = parseFieldSchema(ds.getFieldSchema());
            QueryResult result = QueryResult.of(rows, fields);
            // 缓存
            try {
                redis.opsForValue().set(cacheKey, JsonUtil.toJson(result), ttl);
            } catch (Exception e) {
                log.warn("redis cache write failed for {}: {}", cacheKey, e.getMessage());
            }
            return result;
        } finally {
            lock.unlock();
            localLocks.remove(cacheKey);
        }
    }

    @Async
    public void logQuery(UUID datasetId, UUID dashboardId, UUID tenantId,
                         String sql, long durationMs, int rows, boolean cacheHit) {
        try {
            QueryLog log = new QueryLog();
            log.setTenantId(tenantId);
            log.setDatasetId(datasetId);
            log.setDashboardId(dashboardId);
            log.setSqlMd5(md5(sql));
            log.setDurationMs((int) durationMs);
            log.setRows(rows);
            log.setCacheHit(cacheHit);
            log.setCreatedBy(TenantContext.getUserId());
            queryLogRepo.save(log);
        } catch (Exception e) {
            DatasetQueryService.log.warn("query_log save failed: {}", e.getMessage());
        }
    }

    private List<DatasetDTO.FieldSchema> parseFieldSchema(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JsonUtil.mapper().readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String md5(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] b = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
