package com.onelake.catalog.service;

import com.onelake.catalog.domain.entity.LineageEdge;
import com.onelake.catalog.repository.AssetRepository;
import com.onelake.catalog.repository.LineageEdgeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private LineageEdgeRepository lineageRepo;
    private CatalogService service;

    @BeforeEach
    void setUp() {
        AssetRepository assetRepo = mock(AssetRepository.class);
        lineageRepo = mock(LineageEdgeRepository.class);
        service = new CatalogService(assetRepo, lineageRepo);
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

    private LineageEdge edge(String upstream, String downstream) {
        LineageEdge edge = new LineageEdge();
        edge.setTenantId(TENANT_ID);
        edge.setUpstreamFqn(upstream);
        edge.setDownstreamFqn(downstream);
        return edge;
    }
}
