package com.onelake.integration.domain.entity;

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
 * 源端 schema 快照（漂移检测，对应《技术初始化文档》§7.9 integration.source_schema_snapshot）。
 */
@Entity
@Table(name = "source_schema_snapshot", schema = "integration")
@Getter
@Setter
public class SourceSchemaSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID sourceId;

    @Column(nullable = false)
    private String objectName;        // 库.表

    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String columns;

    @Column(nullable = false)
    private String checksum;

    private Instant capturedAt = Instant.now();
}
