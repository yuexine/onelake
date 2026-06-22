package com.onelake.catalog.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AssetDetailDTO(
    AssetDTO asset,
    LineageSummaryDTO lineage,
    QualitySummaryDTO quality,
    SecuritySummaryDTO security,
    SubscriptionSummaryDTO subscription
) {
    public record LineageSummaryDTO(
        List<LineageEdgeDTO> upstream,
        List<LineageEdgeDTO> downstream,
        List<String> downstreamFqns
    ) {}

    public record LineageEdgeDTO(
        String upstreamFqn,
        String downstreamFqn,
        List<ColumnLineageDTO> columns,
        String jobRef,
        Instant syncedAt
    ) {}

    public record ColumnLineageDTO(
        String from,
        String to,
        String transform
    ) {}

    public record QualitySummaryDTO(
        BigDecimal score,
        int ruleCount,
        int failedRuleCount,
        Instant latestCheckedAt,
        List<QualityRuleStatusDTO> rules
    ) {}

    public record QualityRuleStatusDTO(
        UUID ruleId,
        String ruleType,
        String targetColumn,
        String severity,
        Boolean passed,
        BigDecimal passRate,
        Long failedRows,
        Instant checkedAt
    ) {}

    public record SecuritySummaryDTO(
        String classification,
        int sensitiveColumnCount,
        int activeGrantCount,
        int maskingPolicyCount,
        int piiDetectionCount
    ) {}

    public record SubscriptionSummaryDTO(
        int apiCount,
        int publishedApiCount,
        int approvedSubscriptionCount,
        long callCount,
        int popularity
    ) {}
}
