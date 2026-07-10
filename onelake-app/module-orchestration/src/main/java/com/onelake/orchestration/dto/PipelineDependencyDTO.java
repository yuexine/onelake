package com.onelake.orchestration.dto;

import com.onelake.orchestration.domain.entity.PipelineDependency;

import java.time.Instant;
import java.util.UUID;

/** 流水线周期依赖的稳定 API 投影。 */
public record PipelineDependencyDTO(
        UUID id,
        UUID downstreamDagId,
        UUID upstreamDagId,
        String dependencyType,
        String offsetGrain,
        Integer offsetN,
        Boolean enabled,
        Instant createdAt
) {
    public static PipelineDependencyDTO of(PipelineDependency dependency) {
        return new PipelineDependencyDTO(
                dependency.getId(),
                dependency.getDownstreamDagId(),
                dependency.getUpstreamDagId(),
                dependency.getDependencyType(),
                dependency.getOffsetGrain(),
                dependency.getOffsetN(),
                dependency.getEnabled(),
                dependency.getCreatedAt());
    }
}
