package com.onelake.orchestration.service;

import com.onelake.orchestration.domain.entity.AssetQualityState;
import com.onelake.orchestration.domain.entity.AssetQualityStateId;
import com.onelake.orchestration.domain.entity.AssetReadiness;
import com.onelake.orchestration.dto.SensorReadinessDTO;
import com.onelake.orchestration.repository.AssetQualityStateRepository;
import com.onelake.orchestration.repository.AssetReadinessRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SensorReadinessServiceTest {

    @Mock private AssetReadinessRepository readinessRepository;
    @Mock private AssetQualityStateRepository qualityStateRepository;

    @Test
    void matchesRequestedPartitionFromAssetReadiness() {
        UUID tenantId = UUID.randomUUID();
        AssetReadiness ready = readiness("2026-07-12", "2026-07-12T01:00:00Z");
        when(readinessRepository.findFirstByTenantIdAndAssetFqnAndBatchIdOrderByReadyAtDesc(
                tenantId, "onelake.ods.orders", "2026-07-12")).thenReturn(Optional.of(ready));

        SensorReadinessDTO result = new SensorReadinessService(
                readinessRepository, qualityStateRepository)
                .readiness(tenantId, " onelake.ods.orders ", "2026-07-12");

        assertThat(result.ready()).isTrue();
        assertThat(result.source()).isEqualTo("asset_readiness");
        assertThat(result.partition()).isEqualTo("2026-07-12");
        assertThat(result.readyAt()).isEqualTo(Instant.parse("2026-07-12T01:00:00Z"));
    }

    @Test
    void usesLatestAssetReadinessWhenPartitionIsNotSpecified() {
        UUID tenantId = UUID.randomUUID();
        String fqn = "onelake.ods.orders";
        AssetReadiness latest = readiness("2026-07-13", "2026-07-13T01:00:00Z");
        when(readinessRepository.findFirstByTenantIdAndAssetFqnOrderByReadyAtDesc(tenantId, fqn))
                .thenReturn(Optional.of(latest));

        SensorReadinessDTO result = new SensorReadinessService(
                readinessRepository, qualityStateRepository)
                .readiness(tenantId, fqn, null);

        assertThat(result.ready()).isTrue();
        assertThat(result.partition()).isEqualTo("2026-07-13");
    }

    @Test
    void fallsBackToDurableAssetUpdateState() {
        UUID tenantId = UUID.randomUUID();
        String fqn = "onelake.ods.orders";
        when(readinessRepository.findFirstByTenantIdAndAssetFqnAndBatchIdOrderByReadyAtDesc(
                tenantId, fqn, "2026-07-12")).thenReturn(Optional.empty());
        AssetQualityState state = new AssetQualityState();
        state.setTenantId(tenantId);
        state.setAssetFqn(fqn);
        state.setUpdateEventId(UUID.randomUUID());
        state.setUpdateBatchId("2026-07-12");
        state.setUpdateOccurredAt(Instant.parse("2026-07-12T02:00:00Z"));
        when(qualityStateRepository.findById(new AssetQualityStateId(tenantId, fqn)))
                .thenReturn(Optional.of(state));

        SensorReadinessDTO result = new SensorReadinessService(
                readinessRepository, qualityStateRepository)
                .readiness(tenantId, fqn, "2026-07-12");

        assertThat(result.ready()).isTrue();
        assertThat(result.source()).isEqualTo("asset_quality_state");
    }

    @Test
    void remainsNotReadyWhenPartitionDoesNotMatch() {
        UUID tenantId = UUID.randomUUID();
        String fqn = "onelake.ods.orders";
        when(readinessRepository.findFirstByTenantIdAndAssetFqnAndBatchIdOrderByReadyAtDesc(
                tenantId, fqn, "2026-07-12")).thenReturn(Optional.empty());
        when(qualityStateRepository.findById(new AssetQualityStateId(tenantId, fqn)))
                .thenReturn(Optional.empty());

        SensorReadinessDTO result = new SensorReadinessService(
                readinessRepository, qualityStateRepository)
                .readiness(tenantId, fqn, "2026-07-12");

        assertThat(result.ready()).isFalse();
        assertThat(result.source()).isEqualTo("none");
    }

    private static AssetReadiness readiness(String batchId, String readyAt) {
        AssetReadiness readiness = new AssetReadiness();
        readiness.setBatchId(batchId);
        readiness.setReadyAt(Instant.parse(readyAt));
        return readiness;
    }
}
