package com.onelake.catalog.service;

import com.onelake.catalog.domain.entity.Asset;
import com.onelake.catalog.domain.entity.LineageEdge;
import com.onelake.catalog.dto.AssetDTO;
import com.onelake.catalog.dto.AssetMetadataUpdateRequest;
import com.onelake.catalog.repository.AssetRepository;
import com.onelake.catalog.repository.LineageEdgeRepository;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.util.JsonUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private AssetRepository assetRepo;
    private LineageEdgeRepository lineageRepo;
    private CatalogRowCountResolver rowCountResolver;
    private JdbcTemplate jdbc;
    private CatalogService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        assetRepo = mock(AssetRepository.class);
        lineageRepo = mock(LineageEdgeRepository.class);
        rowCountResolver = mock(CatalogRowCountResolver.class);
        jdbc = mock(JdbcTemplate.class);
        when(rowCountResolver.resolve(anyCollection())).thenReturn(Map.of());
        service = new CatalogService(assetRepo, lineageRepo, rowCountResolver, jdbc);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void downstreamReturnsFullTransitiveImpact() {
        when(lineageRepo.findByTenantIdAndUpstreamFqn(TENANT_ID, "ods.orders"))
            .thenReturn(List.of(edge("ods.orders", "dwd.dwd_order_df")));
        when(lineageRepo.findByTenantIdAndUpstreamFqn(TENANT_ID, "dwd.dwd_order_df"))
            .thenReturn(List.of(edge("dwd.dwd_order_df", "dws.dws_user_order")));
        when(lineageRepo.findByTenantIdAndUpstreamFqn(TENANT_ID, "dws.dws_user_order"))
            .thenReturn(List.of(edge("dws.dws_user_order", "ads.ads_sales_df")));
        when(lineageRepo.findByTenantIdAndUpstreamFqn(TENANT_ID, "ads.ads_sales_df"))
            .thenReturn(List.of());

        assertThat(service.downstream(TENANT_ID, "ods.orders"))
            .containsExactly("dwd.dwd_order_df", "dws.dws_user_order", "ads.ads_sales_df");
    }

    @Test
    void downstreamSkipsCycles() {
        when(lineageRepo.findByTenantIdAndUpstreamFqn(TENANT_ID, "ods.orders"))
            .thenReturn(List.of(edge("ods.orders", "dwd.dwd_order_df")));
        when(lineageRepo.findByTenantIdAndUpstreamFqn(TENANT_ID, "dwd.dwd_order_df"))
            .thenReturn(List.of(edge("dwd.dwd_order_df", "ods.orders"), edge("dwd.dwd_order_df", "ads.ads_sales_df")));
        when(lineageRepo.findByTenantIdAndUpstreamFqn(TENANT_ID, "ads.ads_sales_df"))
            .thenReturn(List.of());

        assertThat(service.downstream(TENANT_ID, "ods.orders"))
            .containsExactly("dwd.dwd_order_df", "ads.ads_sales_df");
    }

    @Test
    void listByLayerUsesLiveRowCountWhenTrinoCountIsAvailable() {
        UUID assetId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        Asset asset = asset(assetId, "ods.ods_customers_100k", 10L);
        when(assetRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(asset));
        when(rowCountResolver.resolve(anyCollection())).thenReturn(Map.of(assetId, 2L));

        assertThat(service.listByLayer(null).get(0).rows()).isEqualTo(2L);
    }

    @Test
    void updateMetadataPatchesCatalogFieldsWithoutChangingPhysicalSchema() {
        UUID assetId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        Asset asset = asset(assetId, "dwd.user_order_wide", 10L);
        asset.setDescription("旧描述");
        asset.setDomain("交易");
        asset.setOwnerName("张三");
        asset.setTags(JsonUtil.toJson(List.of("旧标签")));
        asset.setColumns("""
            [
              {"name":"order_id","type":"BIGINT","description":"旧订单号","classification":"L2"},
              {"name":"phone","type":"VARCHAR","description":"旧手机号","classification":"L3","piiType":"PHONE"}
            ]
            """);
        when(assetRepo.findById(assetId)).thenReturn(java.util.Optional.of(asset));
        when(assetRepo.save(any(Asset.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AssetDTO updated = service.updateMetadata(assetId, new AssetMetadataUpdateRequest(
            "用户订单宽表",
            "用户",
            "李四",
            List.of("核心", "用户", "核心"),
            List.of(
                new AssetMetadataUpdateRequest.ColumnMetadataUpdateRequest(
                    "order_id",
                    "订单主键",
                    "L1",
                    null,
                    null,
                    true
                ),
                new AssetMetadataUpdateRequest.ColumnMetadataUpdateRequest(
                    "phone",
                    "收货手机号",
                    "L4",
                    "PHONE",
                    "L4",
                    false
                )
            )
        ));

        assertThat(updated.description()).isEqualTo("用户订单宽表");
        assertThat(updated.domain()).isEqualTo("用户");
        assertThat(updated.ownerName()).isEqualTo("李四");
        assertThat(updated.tags()).containsExactly("核心", "用户");
        assertThat(updated.classification()).isEqualTo("L4");
        assertThat(updated.columns()).hasSize(2);
        assertThat(updated.columns().get(0).name()).isEqualTo("order_id");
        assertThat(updated.columns().get(0).type()).isEqualTo("BIGINT");
        assertThat(updated.columns().get(0).description()).isEqualTo("订单主键");
        assertThat(updated.columns().get(0).primaryKey()).isTrue();
        assertThat(updated.columns().get(1).type()).isEqualTo("VARCHAR");
        assertThat(updated.columns().get(1).description()).isEqualTo("收货手机号");
        assertThat(updated.columns().get(1).piiType()).isEqualTo("PHONE");
        assertThat(updated.columns().get(1).suggestLevel()).isEqualTo("L4");
    }

    @Test
    void updateMetadataRejectsUnknownColumns() {
        UUID assetId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        Asset asset = asset(assetId, "dwd.user_order_wide", 10L);
        asset.setColumns("""
            [{"name":"order_id","type":"BIGINT"}]
            """);
        when(assetRepo.findById(assetId)).thenReturn(java.util.Optional.of(asset));

        assertThatThrownBy(() -> service.updateMetadata(assetId, new AssetMetadataUpdateRequest(
            null,
            null,
            null,
            null,
            List.of(new AssetMetadataUpdateRequest.ColumnMetadataUpdateRequest(
                "missing_column",
                "不存在",
                "L1",
                null,
                null,
                false
            ))
        )))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("字段不存在");
    }

    private LineageEdge edge(String upstream, String downstream) {
        LineageEdge edge = new LineageEdge();
        edge.setTenantId(TENANT_ID);
        edge.setUpstreamFqn(upstream);
        edge.setDownstreamFqn(downstream);
        return edge;
    }

    private Asset asset(UUID id, String fqn, Long rowCount) {
        Asset asset = new Asset();
        asset.setId(id);
        asset.setTenantId(TENANT_ID);
        asset.setOmFqn(fqn);
        asset.setAssetType("TABLE");
        asset.setLayer("ODS");
        asset.setDisplayName(fqn.substring(fqn.lastIndexOf('.') + 1));
        asset.setRowCount(rowCount);
        asset.setTags("[]");
        asset.setColumns("[]");
        asset.setPartitions("[]");
        return asset;
    }
}
