package com.onelake.catalog.event;

import com.onelake.catalog.domain.entity.Asset;
import com.onelake.catalog.repository.AssetRepository;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxEvent;
import com.onelake.common.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PiiDetectedEventHandlerTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private AssetRepository assetRepo;
    private PiiDetectedEventHandler handler;

    @BeforeEach
    void setUp() {
        assetRepo = mock(AssetRepository.class);
        handler = new PiiDetectedEventHandler(assetRepo);
    }

    @Test
    void createsCatalogAssetWhenPiiDetectedBeforeTableLoaded() {
        when(assetRepo.findByTenantIdAndOmFqn(TENANT_ID, "ods.customers")).thenReturn(Optional.empty());

        handler.handle(event(Map.of(
            "tenantId", TENANT_ID.toString(),
            "tableFqn", "ods.customers",
            "detectionCount", 2,
            "detections", List.of(
                detection("ods.customers.phone_hash", "phone_hash", "手机号", "L3", 0.98),
                detection("ods.customers.id_card_hash", "id_card_hash", "身份证", "L4", 0.95)
            )
        )));

        org.mockito.ArgumentCaptor<Asset> captor = org.mockito.ArgumentCaptor.forClass(Asset.class);
        verify(assetRepo).save(captor.capture());
        Asset asset = captor.getValue();
        assertThat(asset.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(asset.getOmFqn()).isEqualTo("ods.customers");
        assertThat(asset.getAssetType()).isEqualTo("TABLE");
        assertThat(asset.getLayer()).isEqualTo("ODS");
        assertThat(asset.getDisplayName()).isEqualTo("customers");
        assertThat(asset.getClassification()).isEqualTo("L4");
        assertThat(asset.getColumns()).contains(
            "\"name\":\"phone_hash\"",
            "\"piiType\":\"手机号\"",
            "\"classification\":\"L4\"",
            "\"suggestLevel\":\"L4\""
        );
    }

    @Test
    void mergesPiiDetectionsIntoExistingColumns() {
        Asset existing = new Asset();
        existing.setTenantId(TENANT_ID);
        existing.setOmFqn("ods.customers");
        existing.setAssetType("TABLE");
        existing.setColumns(JsonUtil.toJson(List.of(
            Map.of("name", "phone_hash", "type", "STRING", "description", "源字段: phone"),
            Map.of("name", "age", "type", "INT")
        )));
        when(assetRepo.findByTenantIdAndOmFqn(TENANT_ID, "ods.customers")).thenReturn(Optional.of(existing));

        handler.handle(event(Map.of(
            "tenantId", TENANT_ID.toString(),
            "tableFqn", "ods.customers",
            "detectionCount", 1,
            "detections", List.of(detection("ods.customers.phone_hash", "phone_hash", "手机号", "L3", 0.98))
        )));

        verify(assetRepo).save(existing);
        assertThat(existing.getColumns()).contains(
            "\"name\":\"phone_hash\"",
            "\"type\":\"STRING\"",
            "\"description\":\"源字段: phone\"",
            "\"piiType\":\"手机号\"",
            "\"suggestLevel\":\"L3\"",
            "\"name\":\"age\""
        );
    }

    private Map<String, Object> detection(String fqn, String column, String piiType, String suggestLevel, double confidence) {
        return Map.of(
            "fqn", fqn,
            "column", column,
            "piiType", piiType,
            "confidence", confidence,
            "suggestLevel", suggestLevel,
            "status", "PENDING"
        );
    }

    private OutboxEvent event(Map<String, Object> payload) {
        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID());
        event.setTenantId(TENANT_ID);
        event.setEventType(DomainEvents.SECURITY_PII_DETECTED);
        event.setAggregateId("ods.customers");
        event.setPayload(JsonUtil.toJson(payload));
        return event;
    }
}
