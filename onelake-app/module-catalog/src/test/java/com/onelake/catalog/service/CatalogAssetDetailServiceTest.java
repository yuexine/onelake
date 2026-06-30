package com.onelake.catalog.service;

import com.onelake.catalog.domain.entity.LineageEdge;
import com.onelake.catalog.dto.AssetDTO;
import com.onelake.catalog.dto.AssetDetailDTO;
import com.onelake.catalog.repository.LineageEdgeRepository;
import com.onelake.common.context.TenantContext;
import com.onelake.common.util.JsonUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogAssetDetailServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ASSET_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private CatalogService catalogService;
    private LineageEdgeRepository lineageRepo;
    private JdbcTemplate jdbc;
    private CatalogAssetDetailService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        catalogService = mock(CatalogService.class);
        lineageRepo = mock(LineageEdgeRepository.class);
        jdbc = mock(JdbcTemplate.class);
        service = new CatalogAssetDetailService(catalogService, lineageRepo, jdbc);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void getDetailAggregatesLineageQualitySecurityAndSubscription() {
        AssetDTO asset = new AssetDTO(
            ASSET_ID,
            "ods.orders",
            "orders",
            "TABLE",
            "ODS",
            "交易",
            UUID.randomUUID(),
            "张三",
            "订单表",
            List.of("交易"),
            "L3",
            new BigDecimal("92.00"),
            7,
            12,
            100L,
            2048L,
            List.of(
                new AssetDTO.AssetColumnDTO("order_id", "BIGINT", "订单号", null, null, null, false, List.of()),
                new AssetDTO.AssetColumnDTO("phone", "VARCHAR", "手机号", "L3", "PHONE", "L3", false, List.of())
            ),
            List.of("dt"),
            "ICEBERG",
            Instant.parse("2026-06-22T01:00:00Z"),
            Instant.parse("2026-06-22T01:05:00Z")
        );

        when(catalogService.getAsset(ASSET_ID)).thenReturn(asset);
        when(catalogService.downstream(TENANT_ID, "ods.orders")).thenReturn(List.of("dwd.orders", "ads.orders"));
        when(lineageRepo.findByTenantIdAndDownstreamFqn(TENANT_ID, "ods.orders"))
            .thenReturn(List.of(edge("mysql.orders", "ods.orders")));
        when(lineageRepo.findByTenantIdAndUpstreamFqn(TENANT_ID, "ods.orders"))
            .thenReturn(List.of(edge("ods.orders", "dwd.orders")));

        AssetDetailDTO.QualityRuleStatusDTO rule = new AssetDetailDTO.QualityRuleStatusDTO(
            UUID.randomUUID(),
            "NOT_NULL",
            "order_id",
            "BLOCK",
            true,
            new BigDecimal("99.00"),
            0L,
            Instant.parse("2026-06-22T02:00:00Z")
        );
        when(jdbc.query(
            contains("FROM quality.rule"),
            any(RowMapper.class),
            eq(TENANT_ID),
            eq("ods.orders")
        )).thenReturn(List.of(rule));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("security.access_grant")) return 3;
            if (sql.contains("security.masking_policy")) return 2;
            if (sql.contains("security.pii_scan_record")) return 1;
            if (sql.contains("status = 'PUBLISHED'")) return 2;
            if (sql.contains("sub.status = 'APPROVED'")) return 5;
            if (sql.contains("dataservice.api_definition")) return 4;
            return 0;
        });
        when(jdbc.queryForObject(contains("api_call_log"), eq(Long.class), any(Object[].class))).thenReturn(42L);

        AssetDetailDTO detail = service.getDetail(ASSET_ID);

        assertThat(detail.asset().fqn()).isEqualTo("ods.orders");
        assertThat(detail.lineage().upstream()).hasSize(1);
        assertThat(detail.lineage().downstream()).hasSize(1);
        assertThat(detail.lineage().downstreamFqns()).containsExactly("dwd.orders", "ads.orders");
        assertThat(detail.lineage().downstream().get(0).columns()).hasSize(1);
        assertThat(detail.quality().score()).isEqualByComparingTo("99.00");
        assertThat(detail.quality().ruleCount()).isEqualTo(1);
        assertThat(detail.security().classification()).isEqualTo("L3");
        assertThat(detail.security().sensitiveColumnCount()).isEqualTo(1);
        assertThat(detail.security().activeGrantCount()).isEqualTo(3);
        assertThat(detail.subscription().apiCount()).isEqualTo(4);
        assertThat(detail.subscription().publishedApiCount()).isEqualTo(2);
        assertThat(detail.subscription().approvedSubscriptionCount()).isEqualTo(5);
        assertThat(detail.subscription().callCount()).isEqualTo(42L);
        assertThat(detail.subscription().popularity()).isEqualTo(7);
    }

    private LineageEdge edge(String upstream, String downstream) {
        LineageEdge edge = new LineageEdge();
        edge.setTenantId(TENANT_ID);
        edge.setUpstreamFqn(upstream);
        edge.setDownstreamFqn(downstream);
        edge.setColumnLevel(JsonUtil.toJson(List.of(Map.of("from", "id", "to", "order_id"))));
        edge.setJobRef("run-001");
        edge.setSyncedAt(Instant.parse("2026-06-22T01:30:00Z"));
        return edge;
    }
}
