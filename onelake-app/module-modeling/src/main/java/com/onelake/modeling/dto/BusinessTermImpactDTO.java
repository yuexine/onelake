package com.onelake.modeling.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BusinessTermImpactDTO(
    UUID termId,
    String termCode,
    String termName,
    String status,
    Integer version,
    String sensitivityLevel,
    List<BusinessTermBindingDTO> bindings,
    List<AssetImpactDTO> downstreamAssets,
    List<QualityRuleImpactDTO> qualityRules,
    List<ApiImpactDTO> apis,
    List<DagImpactDTO> dags,
    List<SecurityNoticeDTO> securityNotices,
    List<ApprovalImpactDTO> approvals,
    List<String> warnings,
    Integer impactScore
) {
    public record AssetImpactDTO(
        UUID id,
        String fqn,
        String displayName,
        String layer,
        String relation
    ) {}

    public record QualityRuleImpactDTO(
        UUID id,
        String targetFqn,
        String targetColumn,
        String ruleType,
        String severity,
        Boolean enabled
    ) {}

    public record ApiImpactDTO(
        UUID id,
        String apiPath,
        String sourceFqn,
        String status
    ) {}

    public record DagImpactDTO(
        UUID id,
        String name,
        String dagsterJob,
        Boolean enabled
    ) {}

    public record SecurityNoticeDTO(
        String type,
        String fqn,
        String level,
        String status,
        String message
    ) {}

    public record ApprovalImpactDTO(
        UUID id,
        String requestType,
        String targetRef,
        String status,
        Instant createdAt
    ) {}
}
