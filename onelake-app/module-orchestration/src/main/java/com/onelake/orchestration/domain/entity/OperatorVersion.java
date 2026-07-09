package com.onelake.orchestration.domain.entity;

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
 * 算子 Manifest 版本实体。
 */
@Entity
@Table(name = "operator_version", schema = "orchestration")
@Getter
@Setter
public class OperatorVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID operatorId;

    @Column(nullable = false, length = 24)
    private String version;

    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String manifest;

    @Column(columnDefinition = "TEXT")
    private String changelog;

    private UUID createdBy;

    private Instant createdAt = Instant.now();
}
