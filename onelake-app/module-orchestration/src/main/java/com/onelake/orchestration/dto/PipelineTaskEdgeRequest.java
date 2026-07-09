package com.onelake.orchestration.dto;

/**
 * 创建流水线边的请求体。
 */
public record PipelineTaskEdgeRequest(
        String sourceKey,
        String targetKey,
        String edgeLayer,    // 边类型：PIPELINE | CROSS_ENGINE；默认 PIPELINE。
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
