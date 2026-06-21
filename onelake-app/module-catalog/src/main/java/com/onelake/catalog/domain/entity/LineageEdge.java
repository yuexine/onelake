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
 * 血缘边（对应《技术初始化文档》§7.4 catalog.lineage_edge）。
 */
@Entity
@Table(name = "lineage_edge", schema = "catalog")
@Getter
@Setter
public class LineageEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String upstreamFqn;

    @Column(nullable = false)
    private String downstreamFqn;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String columnLevel;

    private String jobRef;

    private Instant syncedAt = Instant.now();
}
