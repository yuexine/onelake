package com.onelake.catalog.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onelake.catalog.domain.entity.AssetConsumer;
import com.onelake.catalog.repository.AssetConsumerRepository;
import com.onelake.common.outbox.DomainEventHandler;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 消费 dataservice / orchestration 投出的 consumer 事件，维护 catalog.asset_consumer 投影表
 * （对应《血缘图模块完善设计方案》§5.1.2）。
 *
 * <p>当前消费事件：
 * <ul>
 *   <li>{@code dataservice.api.published}   → upsert API consumer</li>
 *   <li>{@code dataservice.api.offline}     → REMOVED API consumer</li>
 *   <li>{@code dataservice.subscription.approved} → upsert SUBSCRIPTION consumer（待生产方上线）</li>
 *   <li>{@code dataservice.subscription.revoked}  → REMOVED SUBSCRIPTION consumer（待生产方上线）</li>
 *   <li>{@code orchestration.job.bound}     → upsert JOB consumer（待生产方上线）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssetConsumerEventHandler implements DomainEventHandler {

    private final AssetConsumerRepository consumerRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Set<String> eventTypes() {
        return Set.of(
            DomainEvents.DATASERVICE_API_PUBLISHED,
            DomainEvents.DATASERVICE_API_OFFLINE,
            DomainEvents.DATASERVICE_SUBSCRIPTION_APPROVED,
            DomainEvents.DATASERVICE_SUBSCRIPTION_REVOKED,
            DomainEvents.ORCHESTRATION_JOB_BOUND
        );
    }

    @Override
    @Transactional
    public void handle(OutboxEvent event) {
        try {
            JsonNode p = objectMapper.readTree(event.getPayload() == null ? "{}" : event.getPayload());
            String tenantIdRaw = p.path("tenantId").asText("");
            String assetFqn = p.path("assetFqn").asText("");
            String consumerType = p.path("consumerType").asText("");
            String consumerRef = p.path("consumerRef").asText("");
            String action = p.path("action").asText("UPSERT");

            if (tenantIdRaw.isBlank() || assetFqn.isBlank() || consumerType.isBlank() || consumerRef.isBlank()) {
                log.warn("AssetConsumerEventHandler skipped event {} (missing required fields)", event.getId());
                return;
            }

            UUID tenantId;
            try {
                tenantId = UUID.fromString(tenantIdRaw);
            } catch (IllegalArgumentException e) {
                log.warn("AssetConsumerEventHandler skipped event {} (bad tenantId {})", event.getId(), tenantIdRaw);
                return;
            }

            boolean remove = "REMOVE".equalsIgnoreCase(action)
                || DomainEvents.DATASERVICE_API_OFFLINE.equals(event.getEventType())
                || DomainEvents.DATASERVICE_SUBSCRIPTION_REVOKED.equals(event.getEventType());

            AssetConsumer c = consumerRepo
                .findByTenantIdAndAssetFqnAndConsumerTypeAndConsumerRef(tenantId, assetFqn, consumerType, consumerRef)
                .orElseGet(() -> {
                    AssetConsumer fresh = new AssetConsumer();
                    fresh.setTenantId(tenantId);
                    fresh.setAssetFqn(assetFqn);
                    fresh.setConsumerType(consumerType);
                    fresh.setConsumerRef(consumerRef);
                    return fresh;
                });

            c.setSyncedAt(Instant.now());
            if (remove) {
                c.setStatus("REMOVED");
            } else {
                c.setStatus("ACTIVE");
                if (!p.path("consumerName").isMissingNode()) c.setConsumerName(p.path("consumerName").asText(null));
                if (!p.path("ownerName").isMissingNode())   c.setOwnerName(p.path("ownerName").asText(null));
            }
            consumerRepo.save(c);
            log.info("AssetConsumer {}: {} {}/{}/{}/{}",
                remove ? "removed" : "upserted", assetFqn, consumerType, consumerRef, tenantId);
        } catch (Exception e) {
            log.error("AssetConsumerEventHandler failed for event {}: {}", event.getId(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }
}
