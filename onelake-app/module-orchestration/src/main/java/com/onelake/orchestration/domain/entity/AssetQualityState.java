package com.onelake.orchestration.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** 租户内单个资产的最新更新与最新质量结果配对状态。 */
@Entity
@Table(name = "asset_quality_state", schema = "orchestration")
@IdClass(AssetQualityStateId.class)
@Getter
@Setter
public class AssetQualityState {

    @Id
    @Column(nullable = false)
    private UUID tenantId;

    @Id
    @Column(nullable = false, length = 512)
    private String assetFqn;

    private UUID updateEventId;

    @Column(length = 64)
    private String updateEventType;

    @Column(length = 128)
    private String updateBatchId;

    @Column(length = 128)
    private String updateRunId;

    private Instant updateLogicalDate;

    @Column(length = 64)
    private String updateFreshnessWindow;

    @Column(length = 128)
    private String updatePipelineId;

    private Instant updateOccurredAt;

    private UUID qualityEventId;

    private Boolean qualityPassed;

    @Column(length = 512)
    private String qualityCorrelationKey;

    private UUID qualityAppliedUpdateEventId;

    private Instant qualityCheckedAt;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
}
