package com.onelake.catalog.service;

import com.onelake.catalog.domain.entity.Asset;
import com.onelake.catalog.domain.entity.LineageEdge;
import com.onelake.catalog.dto.ImpactReportDTO;
import com.onelake.catalog.dto.LineageGraphDTO;
import com.onelake.catalog.repository.AssetConsumerRepository;
import com.onelake.catalog.repository.AssetRepository;
import com.onelake.catalog.repository.LineageEdgeRepository;
import com.onelake.common.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogLineageServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private AssetRepository assetRepo;
    private LineageEdgeRepository lineageRepo;
    private AssetConsumerRepository consumerRepo;
    private CatalogLineageService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        assetRepo = mock(AssetRepository.class);
        lineageRepo = mock(LineageEdgeRepository.class);
        consumerRepo = mock(AssetConsumerRepository.class);
        service = new CatalogLineageService(assetRepo, lineageRepo, consumerRepo, 3, 10, 5);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void graphReturnsUpstreamAndDownstreamNodesAndEdges() {
        // Lineage: ods.orders -> dwd.dwd_order_df -> dws.dws_user_order
        List<LineageEdge> allEdges = new ArrayList<>();
        allEdges.add(edge("ods.orders", "dwd.dwd_order_df"));
        allEdges.add(edge("dwd.dwd_order_df", "dws.dws_user_order"));

        // 按 frontier 集合动态返回对应的下游/上游边
        when(lineageRepo.findByTenantIdAndUpstreamFqnIn(eq(TENANT_ID), anyCollection()))
            .thenAnswer(inv -> filterByUpstream(allEdges, inv.getArgument(1)));
        when(lineageRepo.findByTenantIdAndDownstreamFqnIn(eq(TENANT_ID), anyCollection()))
            .thenAnswer(inv -> filterByDownstream(allEdges, inv.getArgument(1)));

        when(assetRepo.findByTenantIdAndOmFqnIn(eq(TENANT_ID), anyCollection()))
            .thenReturn(List.of());

        LineageGraphDTO dto = service.graph(TENANT_ID, "dwd.dwd_order_df", "BOTH", 3);

        assertThat(dto.rootFqn()).isEqualTo("dwd.dwd_order_df");
        assertThat(dto.nodes()).extracting(LineageGraphDTO.Node::fqn)
            .containsExactlyInAnyOrder("dwd.dwd_order_df", "ods.orders", "dws.dws_user_order");
        assertThat(dto.edges()).hasSize(2);
    }

    @Test
    void graphHonorsDirectionUp() {
        List<LineageEdge> allEdges = new ArrayList<>();
        allEdges.add(edge("ods.orders", "dwd.dwd_order_df"));

        when(lineageRepo.findByTenantIdAndDownstreamFqnIn(eq(TENANT_ID), anyCollection()))
            .thenAnswer(inv -> filterByDownstream(allEdges, inv.getArgument(1)));

        when(assetRepo.findByTenantIdAndOmFqnIn(eq(TENANT_ID), anyCollection()))
            .thenReturn(List.of());

        LineageGraphDTO dto = service.graph(TENANT_ID, "dwd.dwd_order_df", "UP", 3);

        verify(lineageRepo, never()).findByTenantIdAndUpstreamFqnIn(eq(TENANT_ID), anyCollection());
        assertThat(dto.nodes()).extracting(LineageGraphDTO.Node::fqn)
            .containsExactlyInAnyOrder("dwd.dwd_order_df", "ods.orders");
    }

    @Test
    void impactClassifiesHighWhenApiConsumerPresent() {
        when(lineageRepo.findByTenantIdAndUpstreamFqn(TENANT_ID, "ods.orders"))
            .thenReturn(List.of(edge("ods.orders", "dwd.dwd_order_df")));
        when(lineageRepo.findByTenantIdAndUpstreamFqnIn(eq(TENANT_ID), anyCollection()))
            .thenReturn(List.of());

        when(consumerRepo.countActiveByTypeAndFqnIn(eq(TENANT_ID), eq("API"), anyCollection()))
            .thenReturn(2L);
        when(consumerRepo.countActiveByTypeAndFqnIn(eq(TENANT_ID), eq("SUBSCRIPTION"), anyCollection()))
            .thenReturn(0L);
        when(consumerRepo.countActiveByTypeAndFqnIn(eq(TENANT_ID), eq("JOB"), anyCollection()))
            .thenReturn(1L);

        ImpactReportDTO dto = service.impact(TENANT_ID, "ods.orders");

        assertThat(dto.severity()).isEqualTo("HIGH");
        assertThat(dto.affectedApis()).isEqualTo(2);
        assertThat(dto.severityReasons()).anyMatch(r -> r.contains("API"));
    }

    @Test
    void impactClassifiesMediumWhenTouchesDws() {
        when(lineageRepo.findByTenantIdAndUpstreamFqn(TENANT_ID, "dwd.dwd_order_df"))
            .thenReturn(List.of(edge("dwd.dwd_order_df", "dws.dws_user_order")));
        when(lineageRepo.findByTenantIdAndUpstreamFqnIn(eq(TENANT_ID), anyCollection()))
            .thenAnswer(inv -> {
                Collection<String> frontier = inv.getArgument(1);
                // 防止 BFS 反复找到 dws.dws_user_order 形成自环
                return frontier.contains("dwd.dwd_order_df")
                    ? List.of(edge("dwd.dwd_order_df", "dws.dws_user_order"))
                    : List.of();
            });

        when(consumerRepo.countActiveByTypeAndFqnIn(eq(TENANT_ID), eq("API"), anyCollection()))
            .thenReturn(0L);
        when(consumerRepo.countActiveByTypeAndFqnIn(eq(TENANT_ID), eq("SUBSCRIPTION"), anyCollection()))
            .thenReturn(0L);
        when(consumerRepo.countActiveByTypeAndFqnIn(eq(TENANT_ID), eq("JOB"), anyCollection()))
            .thenReturn(0L);

        ImpactReportDTO dto = service.impact(TENANT_ID, "dwd.dwd_order_df");

        assertThat(dto.severity()).isEqualTo("MEDIUM");
        assertThat(dto.severityReasons()).anyMatch(r -> r.contains("DWS/ADS"));
    }

    @Test
    void exportImpactCsvIncludesConsumerRows() {
        when(lineageRepo.findByTenantIdAndUpstreamFqnIn(eq(TENANT_ID), anyCollection()))
            .thenReturn(List.of());

        Asset dwd = asset("dwd.dwd_order_df", "DWD", "dwd_order_df");
        dwd.setClassification("L2");
        dwd.setOwnerName("alice");
        when(assetRepo.findByTenantIdAndOmFqnIn(eq(TENANT_ID), anyCollection()))
            .thenReturn(List.of(dwd));

        com.onelake.catalog.domain.entity.AssetConsumer api = new com.onelake.catalog.domain.entity.AssetConsumer();
        api.setTenantId(TENANT_ID);
        api.setAssetFqn("dwd.dwd_order_df");
        api.setConsumerType("API");
        api.setConsumerRef("api-1");
        api.setConsumerName("/api/order");
        api.setOwnerName("bob");
        api.setStatus("ACTIVE");

        when(consumerRepo.findActiveByTypeAndFqnIn(eq(TENANT_ID), eq("API"), anyCollection()))
            .thenReturn(List.of(api));
        when(consumerRepo.findActiveByTypeAndFqnIn(eq(TENANT_ID), eq("SUBSCRIPTION"), anyCollection()))
            .thenReturn(List.of());
        when(consumerRepo.findActiveByTypeAndFqnIn(eq(TENANT_ID), eq("JOB"), anyCollection()))
            .thenReturn(List.of());

        String csv = service.exportImpactCsv(TENANT_ID, "dwd.dwd_order_df");

        assertThat(csv).startsWith("root_fqn,asset_fqn,layer,classification,owner_name,consumer_type,consumer_name,consumer_owner,severity");
        assertThat(csv).contains("/api/order");
        assertThat(csv).contains("alice");
        assertThat(csv).contains("bob");
        assertThat(csv).contains("HIGH"); // 1 个 API 即 HIGH
    }

    @Test
    void buildImpactSummaryIncludesCounts() {
        com.onelake.catalog.dto.ImpactReportDTO report = new com.onelake.catalog.dto.ImpactReportDTO(
            "dwd.dwd_order_df",
            List.of("dws.dws_user_order"),
            List.of("ads.ads_sales"),
            2, 3, 1,
            "HIGH",
            List.of("影响对外 API 3 个")
        );
        String summary = service.buildImpactSummary(report);
        assertThat(summary).contains("直接下游 1");
        assertThat(summary).contains("间接下游 1");
        assertThat(summary).contains("API 3");
        assertThat(summary).contains("订阅 1");
        assertThat(summary).contains("任务 2");
        assertThat(summary).contains("影响对外 API 3 个");
    }

    private List<LineageEdge> filterByUpstream(List<LineageEdge> all, Collection<String> frontier) {
        return all.stream()
            .filter(e -> frontier.contains(e.getUpstreamFqn()))
            .toList();
    }

    private List<LineageEdge> filterByDownstream(List<LineageEdge> all, Collection<String> frontier) {
        return all.stream()
            .filter(e -> frontier.contains(e.getDownstreamFqn()))
            .toList();
    }

    private LineageEdge edge(String up, String down) {
        LineageEdge e = new LineageEdge();
        e.setTenantId(TENANT_ID);
        e.setUpstreamFqn(up);
        e.setDownstreamFqn(down);
        return e;
    }

    @SuppressWarnings("unused")
    private Asset asset(String fqn, String layer, String display) {
        Asset a = new Asset();
        a.setTenantId(TENANT_ID);
        a.setOmFqn(fqn);
        a.setLayer(layer);
        a.setDisplayName(display);
        a.setAssetType("TABLE");
        return a;
    }
}
