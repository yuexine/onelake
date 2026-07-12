package com.onelake.catalog.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onelake.catalog.domain.entity.Asset;
import com.onelake.catalog.repository.AssetRepository;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.DomainEventHandler;
import com.onelake.common.outbox.OutboxEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 将质量稽核结果反哺到 Catalog 资产画像，驱动分层表浏览中的质量分。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QualityCheckEventHandler implements DomainEventHandler {

    private final AssetRepository assetRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Set<String> eventTypes() {
        return Set.of(DomainEvents.QUALITY_CHECK_COMPLETED, DomainEvents.QUALITY_CHECK_FAILED);
    }

    @Override
    @Transactional
    public void handle(OutboxEvent event) {
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload() == null ? "{}" : event.getPayload());
            if (payload.has("assetQualityFinal")
                    && !payload.path("assetQualityFinal").asBoolean()) {
                log.debug("QualityCheckEventHandler skipped rule-level non-final event {}", event.getId());
                return;
            }
            String tenantIdRaw = payload.path("tenantId").asText("");
            String targetFqn = payload.path("targetFqn").asText("");
            if (tenantIdRaw.isBlank() || targetFqn.isBlank()) {
                log.warn("QualityCheckEventHandler skipped event {} (missing tenantId/targetFqn)", event.getId());
                return;
            }

            UUID tenantId;
            try {
                tenantId = UUID.fromString(tenantIdRaw);
            } catch (IllegalArgumentException e) {
                log.warn("QualityCheckEventHandler skipped event {} (bad tenantId {})", event.getId(), tenantIdRaw);
                return;
            }

            BigDecimal score = scoreOf(payload.path("passRate"));
            if (score == null) {
                log.warn("QualityCheckEventHandler skipped event {} (missing passRate)", event.getId());
                return;
            }

            Asset asset = assetRepo.findByTenantIdAndOmFqn(tenantId, targetFqn).orElse(null);
            if (asset == null) {
                log.info("QualityCheckEventHandler: asset {} not indexed yet, quality score update skipped", targetFqn);
                return;
            }

            asset.setQualityScore(score);
            asset.setSyncedAt(Instant.now());
            assetRepo.save(asset);
            log.info("QualityCheckEventHandler: asset {} qualityScore updated to {}", targetFqn, score);
        } catch (Exception e) {
            log.error("QualityCheckEventHandler failed for event {}: {}", event.getId(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private BigDecimal scoreOf(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isNumber()) return node.decimalValue();
        String text = node.asText("");
        if (text.isBlank()) return null;
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
