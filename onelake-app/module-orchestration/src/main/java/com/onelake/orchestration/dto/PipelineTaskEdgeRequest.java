package com.onelake.orchestration.dto;

import java.util.UUID;

public record PipelineTaskEdgeRequest(
        String sourceKey,
        String targetKey,
        String edgeLayer,    // PIPELINE | CROSS_ENGINE; defaults to PIPELINE
        String sourcePort,
        String targetPort,
        String sourceOutput,
        String targetInput,
        String assetFqn,
        String inputAlias,
        String joinRole,
        String triggerPolicy,
        String freshnessPolicy,
        Boolean auto
) {}
