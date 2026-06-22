package com.onelake.modeling.domain.entity;

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

@Entity
@Table(name = "data_model", schema = "modeling")
@Getter
@Setter
public class DataModel {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 16)
    private String layer;

    private String domain;

    @Column(nullable = false)
    private String sourceFqn;

    @Column(nullable = false)
    private String targetFqn;

    @Column(nullable = false, length = 32)
    private String status = "DRAFT";

    @Column(nullable = false, length = 32)
    private String materialization = "TABLE";

    private String uniqueKey;
    private String incrementalColumn;
    private String partitionExpr;

    @Column(columnDefinition = "text")
    private String sqlText;

    @Column(columnDefinition = "text")
    private String compiledSql;

    private String dbtModelName;
    private UUID orchestrationDagId;
    private String dagsterJob;
    private String artifactPath;
    private UUID lastRunId;
    private String pipelineMode = "SYSTEM_GENERATED";
    private Integer operatorGraphVersion = 1;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String operatorGraph = "{}";

    private String resourceGroup = "default";
    private String computeProfile = "trino-small";
    private String engine = "TRINO_DBT";

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String costPolicy = "{}";

    private UUID ownerId;
    private String ownerName;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
