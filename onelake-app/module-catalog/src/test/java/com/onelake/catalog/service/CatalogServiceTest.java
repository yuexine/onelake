package com.onelake.catalog.service;

import com.onelake.catalog.domain.entity.Asset;
import com.onelake.catalog.domain.entity.LineageEdge;
import com.onelake.catalog.repository.AssetRepository;
import com.onelake.catalog.repository.LineageEdgeRepository;
import com.onelake.common.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
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
