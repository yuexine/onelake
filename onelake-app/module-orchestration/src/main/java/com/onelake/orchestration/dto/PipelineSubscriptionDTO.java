package com.onelake.orchestration.dto;

import com.onelake.orchestration.domain.entity.PipelineSubscription;

import java.time.Instant;
import java.util.UUID;

/** 流水线自动化订阅的稳定 API 投影。 */
public record PipelineSubscriptionDTO(
        UUID id,
        UUID tenantId,
        UUID dagId,
        String sourceType,
        String sourceRef,
        String condition,
        String freshnessPolicy,
        Boolean enabled,
        Instant createdAt
) {
    public static PipelineSubscriptionDTO of(PipelineSubscription subscription) {
        return new PipelineSubscriptionDTO(
                subscription.getId(),
                subscription.getTenantId(),
                subscription.getDagId(),
                subscription.getSourceType(),
                subscription.getSourceRef(),
                subscription.getCondition(),
                subscription.getFreshnessPolicy(),
                subscription.getEnabled(),
                subscription.getCreatedAt());
    }
}
