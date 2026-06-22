package com.onelake.catalog.event;

import com.onelake.catalog.domain.entity.Asset;
import com.onelake.catalog.domain.entity.LineageEdge;
import com.onelake.catalog.repository.AssetRepository;
import com.onelake.catalog.repository.LineageEdgeRepository;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxEvent;
import com.onelake.common.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DwdModelLoadedEventHandlerTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("d0e42034-2349-474a-ba4e-bc6d2127343d");

    private AssetRepository assetRepo;
    private LineageEdgeRepository lineageRepo;
    private DwdModelLoadedEventHandler handler;

    @BeforeEach
    void setUp() {
        assetRepo = mock(AssetRepository.class);
        lineageRepo = mock(LineageEdgeRepository.class);
        when(lineageRepo.findByTenantIdAndUpstreamFqnAndDownstreamFqn(any(), any(), any()))
            .thenReturn(Optional.empty());
        handler = new DwdModelLoadedEventHandler(assetRepo, lineageRepo);
    }

    @Test
    void createsDwdAssetAndLineageFromModelLoadedEvent() {
        when(assetRepo.findByTenantIdAndOmFqn(TENANT_ID, "dwd.dwd_trade_orders_df")).thenReturn(Optional.empty());
        when(lineageRepo.findByTenantIdAndUpstreamFqnAndDownstreamFqn(
            TENANT_ID, "ods.ods_orders", "dwd.dwd_trade_orders_df")).thenReturn(Optional.empty());

        handler.handle(event(Map.of(
            "tenantId", TENANT_ID.toString(),
            "runId", "44444444-4444-4444-4444-444444444444",
            "sourceFqn", "ods.ods_orders",
            "targetFqn", "dwd.dwd_trade_orders_df",
            "ownerId", USER_ID.toString(),
            "ownerName", "dev",
            "rowsWritten", 10,
            "fieldMapping", List.of(
                Map.of("source", "order_id", "target", "order_id", "targetType", "BIGINT"),
                Map.of("source", "phone", "target", "phone_hash", "targetType", "VARCHAR", "classification", "L3", "piiType", "PHONE")
            )
        )));

        ArgumentCaptor<Asset> assetCaptor = ArgumentCaptor.forClass(Asset.class);
        verify(assetRepo).save(assetCaptor.capture());
        Asset asset = assetCaptor.getValue();
        assertThat(asset.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(asset.getOmFqn()).isEqualTo("dwd.dwd_trade_orders_df");
        assertThat(asset.getLayer()).isEqualTo("DWD");
        assertThat(asset.getAssetType()).isEqualTo("TABLE");
        assertThat(asset.getOwnerId()).isEqualTo(USER_ID);
        assertThat(asset.getOwnerName()).isEqualTo("dev");
        assertThat(asset.getRowCount()).isEqualTo(10L);
        assertThat(asset.getTags()).isEqualTo("[\"modeling\",\"dwd\",\"auto\"]");
        assertThat(asset.getColumns()).contains("\"name\":\"order_id\"", "\"name\":\"phone_hash\"", "\"classification\":\"L3\"");

        ArgumentCaptor<LineageEdge> edgeCaptor = ArgumentCaptor.forClass(LineageEdge.class);
        verify(lineageRepo).save(edgeCaptor.capture());
        LineageEdge edge = edgeCaptor.getValue();
        assertThat(edge.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(edge.getUpstreamFqn()).isEqualTo("ods.ods_orders");
        assertThat(edge.getDownstreamFqn()).isEqualTo("dwd.dwd_trade_orders_df");
        assertThat(edge.getJobRef()).isEqualTo("44444444-4444-4444-4444-444444444444");
        assertThat(edge.getColumnLevel()).contains("\"from\":\"order_id\"", "\"to\":\"phone_hash\"");
    }

    @Test
    void updatesExistingDwdAsset() {
        Asset existing = new Asset();
        existing.setTenantId(TENANT_ID);
        existing.setOmFqn("dwd.dwd_trade_orders_df");
        existing.setAssetType("TABLE");
        existing.setLayer("DWD");
        existing.setRowCount(5L);
        when(assetRepo.findByTenantIdAndOmFqn(TENANT_ID, "dwd.dwd_trade_orders_df")).thenReturn(Optional.of(existing));

        handler.handle(event(Map.of(
            "tenantId", TENANT_ID.toString(),
            "sourceFqn", "ods.ods_orders",
            "targetFqn", "dwd.dwd_trade_orders_df",
            "rowsWritten", 12,
            "fieldMapping", List.of(Map.of("source", "order_id", "target", "order_id", "targetType", "BIGINT"))
        )));

        verify(assetRepo).save(existing);
        assertThat(existing.getRowCount()).isEqualTo(12L);
        assertThat(existing.getColumns()).contains("\"name\":\"order_id\"");
    }

    @Test
    void skipsInvalidPayload() {
        handler.handle(event(Map.of(
            "tenantId", "bad",
            "sourceFqn", "ods.ods_orders",
            "targetFqn", "dwd.dwd_trade_orders_df"
        )));

        verify(assetRepo, never()).save(any());
        verify(lineageRepo, never()).save(any());
    }

    private OutboxEvent event(Map<String, Object> payload) {
        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID());
        event.setTenantId(TENANT_ID);
        event.setEventType(DomainEvents.MODELING_MODEL_LOADED);
        event.setAggregateId(UUID.randomUUID().toString());
        event.setPayload(JsonUtil.toJson(payload));
        return event;
    }
}
