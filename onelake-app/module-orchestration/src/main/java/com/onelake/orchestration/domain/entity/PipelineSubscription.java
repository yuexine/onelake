package com.onelake.orchestration.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** 资产或上游流水线对下游流水线的自动触发订阅。 */
@Entity
@Table(name = "pipeline_subscription", schema = "orchestration")
@Getter
@Setter
public class PipelineSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    /** 被触发的下游流水线。 */
    @Column(nullable = false)
    private UUID dagId;

    /** ASSET 或 PIPELINE。 */
    @Column(nullable = false, length = 16)
    private String sourceType;

    /** 资产 FQN 或上游流水线 ID。 */
    @Column(nullable = false, length = 512)
    private String sourceRef;

    /** ON_UPDATE 或 ON_UPDATE_AND_QUALITY_PASS。 */
    @Column(nullable = false, length = 32)
    private String condition = "ON_UPDATE";

    /** LATEST、SAME_FRESHNESS_WINDOW 或 SAME_BATCH。 */
    @Column(nullable = false, length = 32)
    private String freshnessPolicy = "LATEST";

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
