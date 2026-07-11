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

/** 流水线输入资产的持久化就绪状态。 */
@Entity
@Table(name = "asset_readiness", schema = "orchestration")
@IdClass(AssetReadinessId.class)
@Getter
@Setter
public class AssetReadiness {

    @Column(nullable = false)
    private UUID tenantId;

    @Id
    @Column(nullable = false)
    private UUID dagId;

    @Id
    @Column(nullable = false, length = 128)
    private String taskKey;

    @Column(nullable = false, length = 256)
    private String assetFqn;

    @Column(length = 128)
    private String batchId;

    @Column(length = 64)
    private String freshnessWindow;

    @Column(nullable = false)
    private Instant readyAt = Instant.now();
}
