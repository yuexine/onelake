package com.onelake.analytics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onelake.analytics.client.DataServiceClient;
import com.onelake.analytics.client.TrinoQueryClient;
import com.onelake.analytics.domain.entity.Dataset;
import com.onelake.analytics.domain.entity.QueryLog;
import com.onelake.analytics.domain.enums.SourceType;
import com.onelake.analytics.dto.DataBinding;
import com.onelake.analytics.dto.QueryResult;
import com.onelake.analytics.repository.QueryLogRepository;
import com.onelake.common.context.TenantContext;
import com.onelake.common.outbox.OutboxPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DatasetQueryService 单元测试 —— 覆盖 v1.1 评审关键边界：
 * 1. source_type=API 走 dataservice，不入 Trino 缓存
 * 2. source_type=ASSET/SQL 走 Trino + Redis 缓存 + single-flight
 * 3. 异步写 query_log
 * 4. 慢查询触发 Outbox 告警
 */
class DatasetQueryServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private DatasetService datasetService;
    private TrinoQueryClient trino;
    private DataServiceClient dataservice;
    private StringRedisTemplate redis;
    private MaskingPolicyView masking;
    private SqlBuilder sqlBuilder;
    private QueryLogRepository queryLogRepo;
    private OutboxPublisher outbox;

    private DatasetQueryService service;

    @BeforeEach
    void setUp() {
        datasetService = mock(DatasetService.class);
        trino = mock(TrinoQueryClient.class);
        dataservice = mock(DataServiceClient.class);
        redis = mock(StringRedisTemplate.class);
        masking = mock(MaskingPolicyView.class);
        sqlBuilder = mock(SqlBuilder.class);
        queryLogRepo = mock(QueryLogRepository.class);
        outbox = mock(OutboxPublisher.class);

        service = new DatasetQueryService(
            datasetService, trino, dataservice, redis, masking, sqlBuilder, queryLogRepo, outbox);
        // 反射注入 @Value 字段（slowThresholdMs）
        org.springframework.test.util.ReflectionTestUtils.setField(service, "slowThresholdMs", 5L);

        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(USER_ID);
        TenantContext.setUsername("analyst-1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void apiSource_bypassesTrinoAndCache_callsDataService() {
        UUID datasetId = UUID.randomUUID();
        Dataset ds = apiDataset(datasetId);
        when(datasetService.getEntity(datasetId, TENANT_ID)).thenReturn(ds);
        QueryResult apiResult = QueryResult.of(
            List.of(Map.of("k", "v")), List.of());
        when(dataservice.query(eq(ds.getApiId()), any())).thenReturn(apiResult);

        QueryResult result = service.query(datasetId, emptyBinding(), null);

        assertThat(result.getRows()).hasSize(1);
        assertThat(result.isCacheHit()).isFalse();
        verify(trino, never()).query(anyString());
        verify(redis, never()).opsForValue();
    }

    @Test
    void assetSource_usesTrino_andCachesResult() {
        UUID datasetId = UUID.randomUUID();
        Dataset ds = assetDataset(datasetId);
        when(datasetService.getEntity(datasetId, TENANT_ID)).thenReturn(ds);
        when(masking.forTenant(TENANT_ID)).thenReturn(Map.of());
        when(sqlBuilder.compose(eq(ds), any(), eq(Map.of())))
            .thenReturn("SELECT * FROM iceberg.dwd.dwd_user LIMIT 100");
        // 缓存未命中
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(trino.query(anyString())).thenReturn(List.of(Map.of("user_id", "u1")));

        QueryResult result = service.query(datasetId, emptyBinding(), null);

        assertThat(result.getRows()).hasSize(1);
        assertThat(result.isCacheHit()).isFalse();
        // 缓存写入
        verify(valueOps, atLeastOnce()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void assetSource_cacheHit_skipsTrinoQuery() {
        UUID datasetId = UUID.randomUUID();
        Dataset ds = assetDataset(datasetId);
        when(datasetService.getEntity(datasetId, TENANT_ID)).thenReturn(ds);
        when(masking.forTenant(TENANT_ID)).thenReturn(Map.of());
        when(sqlBuilder.compose(eq(ds), any(), eq(Map.of()))).thenReturn("SELECT 1");

        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        // 命中缓存
        QueryResult cached = QueryResult.of(
            List.of(Map.of("user_id", "cached")), List.of());
        when(valueOps.get(anyString())).thenReturn(new ObjectMapper().convertValue(cached, com.fasterxml.jackson.databind.node.ObjectNode.class).toString());
        // 模拟 cached JSON
        when(valueOps.get(anyString())).thenReturn("{\"rows\":[{\"user_id\":\"cached\"}],\"fields\":[],\"cacheHit\":false,\"durationMs\":0}");

        QueryResult result = service.query(datasetId, emptyBinding(), null);

        assertThat(result.isCacheHit()).isTrue();
        assertThat(result.getRows()).hasSize(1);
        verify(trino, never()).query(anyString());
    }

    @Test
    void slowQuery_publishesOutboxAlert() {
        UUID datasetId = UUID.randomUUID();
        Dataset ds = assetDataset(datasetId);
        when(datasetService.getEntity(datasetId, TENANT_ID)).thenReturn(ds);
        when(masking.forTenant(TENANT_ID)).thenReturn(Map.of());
        when(sqlBuilder.compose(eq(ds), any(), eq(Map.of()))).thenReturn("SELECT 1");

        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        // 模拟 Trino 查询耗时 > 5ms（slowThresholdMs=5）
        when(trino.query(anyString())).thenAnswer(invocation -> {
            Thread.sleep(50);
            return List.of();
        });

        service.query(datasetId, emptyBinding(), null);

        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        verify(outbox).publish(typeCaptor.capture(), idCaptor.capture(), any());
        assertThat(typeCaptor.getValue()).isEqualTo("analytics.query.slow");
        assertThat(idCaptor.getValue()).isEqualTo(datasetId.toString());
    }

    @Test
    void queryLog_savedAsync_withTenantAndDatasetId() {
        UUID datasetId = UUID.randomUUID();
        Dataset ds = apiDataset(datasetId);
        when(datasetService.getEntity(datasetId, TENANT_ID)).thenReturn(ds);
        when(dataservice.query(eq(ds.getApiId()), any()))
            .thenReturn(QueryResult.of(List.of(), List.of()));

        service.query(datasetId, emptyBinding(), null);

        // 异步保存（同线程异步执行器），等待短暂时间后 verify
        ArgumentCaptor<QueryLog> captor = ArgumentCaptor.forClass(QueryLog.class);
        verify(queryLogRepo, org.mockito.Mockito.timeout(2000)).save(captor.capture());
        QueryLog log = captor.getValue();
        assertThat(log.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(log.getDatasetId()).isEqualTo(datasetId);
        assertThat(log.getCacheHit()).isFalse();
    }

    // ============ helpers ============

    private Dataset apiDataset(UUID id) {
        Dataset d = new Dataset();
        d.setId(id);
        d.setTenantId(TENANT_ID);
        d.setSourceType(SourceType.API);
        d.setApiId(UUID.randomUUID());
        d.setCacheTtlSec(60);
        return d;
    }

    private Dataset assetDataset(UUID id) {
        Dataset d = new Dataset();
        d.setId(id);
        d.setTenantId(TENANT_ID);
        d.setSourceType(SourceType.ASSET);
        d.setAssetFqn("iceberg.dwd.dwd_user");
        d.setCacheTtlSec(60);
        return d;
    }

    private DataBinding emptyBinding() {
        return DataBinding.builder().dimensions(List.of()).measures(List.of()).build();
    }
}
