package com.onelake.analytics.domain.entity;

import com.onelake.analytics.domain.enums.SourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 数据集：可视化/分析的统一数据入口（对应《数据分析与可视化模块设计方案》§6 analytics.dataset）。
 * source_type=ASSET 仅存 FQN 句柄 + 密级快照，不读 catalog schema。
 */
@Entity
@Table(name = "dataset", schema = "analytics")
@Getter
@Setter
public class Dataset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SourceType sourceType;

    @Column(length = 512)
    private String assetFqn;

    @Column(columnDefinition = "text")
    private String selectSql;

    private UUID apiId;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String fieldSchema;   // [{name,type,classification}]

    @Column(length = 8, nullable = false)
    private String classification = "L1";

    @Column(nullable = false)
    private Integer cacheTtlSec = 300;

    @Column(columnDefinition = "text")
    private String rowFilter;

    private UUID createdBy;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
}
