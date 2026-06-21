package com.onelake.catalog.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnTransformer;

import java.time.Instant;
import java.util.UUID;

/**
 * 资产（对应《技术初始化文档》§7.4 catalog.asset）。
 * OpenMetadata 的本地索引/缓存，权威以 OM 为准。
 */
@Entity
@Table(name = "asset", schema = "catalog")
@Getter
@Setter
public class Asset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String omFqn;

    @Column(nullable = false, length = 32)
    private String assetType;     // TABLE/VIEW/TOPIC/API

    @Column(length = 8)
    private String layer;         // ODS/DWD/DWS/ADS

    private String domain;

    private String displayName;

    @Column(columnDefinition = "text")
    private String description;

    private UUID ownerId;

    private String ownerName;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String tags;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String columns;

    @Column(length = 8)
    private String classification;     // L1/L2/L3/L4 密级

    private java.math.BigDecimal qualityScore;

    private Integer popularity = 0;

    private Integer accessCount = 0;

    private Long rowCount;

    private Long sizeBytes;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String partitions;

    private String format;

    private Instant lastSyncAt;

    private Instant syncedAt = Instant.now();
}
