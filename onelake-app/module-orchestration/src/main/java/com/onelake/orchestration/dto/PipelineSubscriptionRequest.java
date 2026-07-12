package com.onelake.orchestration.dto;

/** 新增资产或上游流水线自动触发订阅的请求。 */
public record PipelineSubscriptionRequest(
        String sourceType,
        String sourceRef,
        String condition,
        String freshnessPolicy
) {
}
