package com.onelake.orchestration.dto;

import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import com.onelake.orchestration.domain.enums.EdgeLayer;

import java.time.Instant;
import java.util.UUID;

public record PipelineTaskEdgeDTO(
        UUID id,
        UUID dagId,
        String sourceKey,
        String targetKey,
        EdgeLayer edgeLayer,
        String sourcePort,
        String targetPort,
        String sourceOutput,
        String targetInput,
        String assetFqn,
        String inputAlias,
        String joinRole,
        String triggerPolicy,
        String freshnessPolicy,
        Boolean auto,
        Instant createdAt
) {
    public static PipelineTaskEdgeDTO of(PipelineTaskEdge e) {
        return new PipelineTaskEdgeDTO(
                e.getId(), e.getDagId(),
                e.getSourceKey(), e.getTargetKey(),
                e.getEdgeLayer(),
                e.getSourcePort(), e.getTargetPort(),
                e.getSourceOutput(), e.getTargetInput(),
                e.getAssetFqn(), e.getInputAlias(), e.getJoinRole(),
                e.getTriggerPolicy(), e.getFreshnessPolicy(),
                e.getAuto(),
                e.getCreatedAt()
        );
    }
}
