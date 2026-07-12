package com.onelake.catalog.event;

import com.onelake.catalog.domain.entity.Asset;
import com.onelake.catalog.repository.AssetRepository;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxEvent;
import com.onelake.common.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QualityCheckEventHandlerTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private AssetRepository assetRepo;
    private QualityCheckEventHandler handler;

    @BeforeEach
    void setUp() {
        assetRepo = mock(AssetRepository.class);
        handler = new QualityCheckEventHandler(assetRepo);
    }

    @Test
    void updatesExistingAssetQualityScore() {
        Asset asset = new Asset();
        asset.setTenantId(TENANT_ID);
        asset.setOmFqn("ods.customers");
        asset.setAssetType("TABLE");
        asset.setQualityScore(new BigDecimal("100.00"));
        when(assetRepo.findByTenantIdAndOmFqn(TENANT_ID, "ods.customers")).thenReturn(Optional.of(asset));

        handler.handle(event(DomainEvents.QUALITY_CHECK_FAILED, Map.of(
            "tenantId", TENANT_ID.toString(),
            "targetFqn", "ods.customers",
            "passRate", new BigDecimal("96.00"),
            "failedRows", 32
        )));

        verify(assetRepo).save(asset);
        assertThat(asset.getQualityScore()).isEqualByComparingTo("96.00");
        assertThat(asset.getSyncedAt()).isNotNull();
    }

    @Test
    void skipsWhenAssetIsNotIndexed() {
        when(assetRepo.findByTenantIdAndOmFqn(TENANT_ID, "ods.missing")).thenReturn(Optional.empty());

        handler.handle(event(DomainEvents.QUALITY_CHECK_COMPLETED, Map.of(
            "tenantId", TENANT_ID.toString(),
            "targetFqn", "ods.missing",
            "passRate", "100.00",
            "failedRows", 0
        )));

        verify(assetRepo, never()).save(any());
    }

    @Test
    void ignoresRuleLevelNonFinalQualityEvent() {
        handler.handle(event(DomainEvents.QUALITY_CHECK_COMPLETED, Map.of(
            "tenantId", TENANT_ID.toString(),
            "targetFqn", "ods.customers",
            "passRate", "100.00",
            "assetQualityFinal", false
        )));

        verify(assetRepo, never()).findByTenantIdAndOmFqn(any(), any());
        verify(assetRepo, never()).save(any());
    }

    @Test
    void skipsInvalidPayload() {
        handler.handle(event(DomainEvents.QUALITY_CHECK_FAILED, Map.of(
            "tenantId", TENANT_ID.toString(),
            "passRate", "bad"
        )));

        verify(assetRepo, never()).save(any());
    }

    private OutboxEvent event(String eventType, Map<String, Object> payload) {
        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID());
        event.setTenantId(TENANT_ID);
        event.setEventType(eventType);
        event.setAggregateId(UUID.randomUUID().toString());
        event.setPayload(JsonUtil.toJson(payload));
        return event;
    }
}
