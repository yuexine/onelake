package com.onelake.orchestration.service;

import com.onelake.orchestration.domain.entity.AssetQualityState;
import com.onelake.orchestration.domain.entity.AssetQualityStateId;
import com.onelake.orchestration.domain.entity.AssetReadiness;
import com.onelake.orchestration.dto.SensorReadinessDTO;
import com.onelake.orchestration.repository.AssetQualityStateRepository;
import com.onelake.orchestration.repository.AssetReadinessRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.UUID;

/** 为 Dagster SENSOR 提供租户隔离、只读的资产/分区就绪判定。 */
@Service
@RequiredArgsConstructor
public class SensorReadinessService {

    private final AssetReadinessRepository readinessRepository;
    private final AssetQualityStateRepository qualityStateRepository;

    @Transactional(readOnly = true)
    public SensorReadinessDTO readiness(UUID tenantId, String assetFqn, String partition) {
        String normalizedFqn = assetFqn == null ? "" : assetFqn.trim();
        String normalizedPartition = StringUtils.hasText(partition) ? partition.trim() : null;

        AssetReadiness readiness = normalizedPartition == null
                ? readinessRepository
                        .findFirstByTenantIdAndAssetFqnOrderByReadyAtDesc(tenantId, normalizedFqn)
                        .orElse(null)
                : readinessRepository
                        .findFirstByTenantIdAndAssetFqnAndBatchIdOrderByReadyAtDesc(
                                tenantId, normalizedFqn, normalizedPartition)
                        .orElse(null);
        if (readiness != null) {
            return new SensorReadinessDTO(
                    true, "asset_readiness", normalizedFqn,
                    readiness.getBatchId(), readiness.getReadyAt());
        }

        AssetQualityState state = qualityStateRepository.findById(
                        new AssetQualityStateId(tenantId, normalizedFqn))
                .orElse(null);
        if (state != null
                && state.getUpdateEventId() != null
                && matchesPartition(normalizedPartition, state.getUpdateBatchId())) {
            return new SensorReadinessDTO(
                    true, "asset_quality_state", normalizedFqn,
                    state.getUpdateBatchId(), state.getUpdateOccurredAt());
        }
        return new SensorReadinessDTO(false, "none", normalizedFqn, normalizedPartition, null);
    }

    private static boolean matchesPartition(String requested, String observed) {
        return requested == null || requested.equals(observed);
    }
}
