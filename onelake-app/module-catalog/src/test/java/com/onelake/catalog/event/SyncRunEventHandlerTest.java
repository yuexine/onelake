package com.onelake.catalog.event;

import com.onelake.catalog.client.OpenMetadataClient;
import com.onelake.catalog.domain.entity.Asset;
import com.onelake.catalog.domain.entity.LineageEdge;
import com.onelake.catalog.repository.AssetRepository;
import com.onelake.catalog.repository.LineageEdgeRepository;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

class SyncRunEventHandlerTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private AssetRepository assetRepo;
    private LineageEdgeRepository lineageRepo;
    private OpenMetadataClient openMetadataClient;
    private SyncRunEventHandler handler;

    @BeforeEach
    void setUp() {
        assetRepo = mock(AssetRepository.class);
        lineageRepo = mock(LineageEdgeRepository.class);
        openMetadataClient = mock(OpenMetadataClient.class);
        handler = new SyncRunEventHandler(assetRepo, lineageRepo, openMetadataClient);
    }

    @Test
    void tableLoadedCreatesCatalogAssetWhenMissing() {
        when(assetRepo.findByTenantIdAndOmFqn(TENANT_ID, "ods.orders")).thenReturn(Optional.empty());

        handler.handle(event(DomainEvents.INTEGRATION_TABLE_LOADED, Map.of(
            "tenantId", TENANT_ID.toString(),
            "runId", "run-001",
            "sourceTable", "mysql.orders",
            "targetTable", "ods.orders",
            "namespace", "ods",
            "table", "orders",
            "status", "SUCCEEDED",
            "fieldMapping", List.of(
                Map.of("source", "id", "target", "id", "sourceType", "bigint", "targetType", "BIGINT"),
                Map.of("source", "phone", "target", "phone_hash", "sourceType", "varchar", "targetType", "STRING", "classification", "L3")
            )
        )));

        verify(assetRepo).save(any(Asset.class));
        org.mockito.ArgumentCaptor<Asset> captor = org.mockito.ArgumentCaptor.forClass(Asset.class);
        verify(assetRepo).save(captor.capture());
        Asset asset = captor.getValue();
        assertThat(asset.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(asset.getOmFqn()).isEqualTo("ods.orders");
        assertThat(asset.getAssetType()).isEqualTo("TABLE");
        assertThat(asset.getLayer()).isEqualTo("ODS");
        assertThat(asset.getDisplayName()).isEqualTo("orders");
        assertThat(asset.getTags()).isEqualTo("[\"integration\",\"auto\"]");
        assertThat(asset.getColumns()).contains("\"name\":\"id\"", "\"name\":\"phone_hash\"", "\"classification\":\"L3\"");
        assertThat(asset.getSyncedAt()).isNotNull();

        org.mockito.ArgumentCaptor<LineageEdge> lineageCaptor = org.mockito.ArgumentCaptor.forClass(LineageEdge.class);
        verify(lineageRepo).save(lineageCaptor.capture());
        LineageEdge edge = lineageCaptor.getValue();
        assertThat(edge.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(edge.getUpstreamFqn()).isEqualTo("mysql.orders");
        assertThat(edge.getDownstreamFqn()).isEqualTo("ods.orders");
        assertThat(edge.getJobRef()).isEqualTo("run-001");
        assertThat(edge.getColumnLevel()).contains("\"from\":\"id\"", "\"to\":\"phone_hash\"");
        verify(openMetadataClient).upsertIntegrationTable(any(), any(), any(), any());
        verify(openMetadataClient).upsertIntegrationLineage("mysql.orders", "ods.orders", "run-001");
    }

    @Test
    void tableLoadedRefreshesExistingAsset() {
        Asset existing = new Asset();
        existing.setTenantId(TENANT_ID);
        existing.setOmFqn("dwd.orders");
        existing.setAssetType("TABLE");
        existing.setLayer("DWD");
        when(assetRepo.findByTenantIdAndOmFqn(TENANT_ID, "dwd.orders")).thenReturn(Optional.of(existing));

        handler.handle(event(DomainEvents.INTEGRATION_TABLE_LOADED, Map.of(
            "tenantId", TENANT_ID.toString(),
            "targetTable", "dwd.orders",
            "status", "SUCCEEDED"
        )));

        verify(assetRepo).save(existing);
        assertThat(existing.getLayer()).isEqualTo("DWD");
        assertThat(existing.getSyncedAt()).isNotNull();
    }

    @Test
    void tableLoadedPreservesExistingPiiAnnotations() {
        Asset existing = new Asset();
        existing.setTenantId(TENANT_ID);
        existing.setOmFqn("ods.customers");
        existing.setAssetType("TABLE");
        existing.setColumns(com.onelake.common.util.JsonUtil.toJson(List.of(
            Map.of(
                "name", "phone_hash",
                "type", "-",
                "classification", "L3",
                "suggestLevel", "L3",
                "piiType", "手机号",
                "piiConfidence", 0.98
            )
        )));
        when(assetRepo.findByTenantIdAndOmFqn(TENANT_ID, "ods.customers")).thenReturn(Optional.of(existing));

        handler.handle(event(DomainEvents.INTEGRATION_TABLE_LOADED, Map.of(
            "tenantId", TENANT_ID.toString(),
            "targetTable", "ods.customers",
            "status", "SUCCEEDED",
            "fieldMapping", List.of(
                Map.of("source", "phone", "target", "phone_hash", "sourceType", "varchar", "targetType", "STRING"),
                Map.of("source", "age", "target", "age", "sourceType", "int", "targetType", "INT")
            )
        )));

        verify(assetRepo).save(existing);
        assertThat(existing.getColumns()).contains(
            "\"name\":\"phone_hash\"",
            "\"type\":\"STRING\"",
            "\"classification\":\"L3\"",
            "\"suggestLevel\":\"L3\"",
            "\"piiType\":\"手机号\"",
            "\"piiConfidence\":0.98",
            "\"name\":\"age\""
        );
    }

    @Test
    void failedSyncDoesNotCreateCatalogAsset() {
        handler.handle(event(DomainEvents.INTEGRATION_SYNC_FAILED, Map.of(
            "tenantId", TENANT_ID.toString(),
            "targetTable", "ods.orders",
            "status", "FAILED"
        )));

        verify(assetRepo, never()).save(any());
        verify(lineageRepo, never()).save(any());
    }

    private OutboxEvent event(String eventType, Map<String, Object> payload) {
        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID());
        event.setTenantId(TENANT_ID);
        event.setEventType(eventType);
        event.setAggregateId(UUID.randomUUID().toString());
        event.setPayload(com.onelake.common.util.JsonUtil.toJson(payload));
        return event;
    }
}
