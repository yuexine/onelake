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
@Table(name = "codebook", schema = "modeling")
@Getter
@Setter
public class Codebook {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 96)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 64)
    private String domain;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false, length = 16)
    private String status = "DRAFT";

    @Column(length = 32)
    private String latestVersion;

    @Column(nullable = false, length = 16)
    private String noMatchPolicy = "KEEP";

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String entries = "[]";

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String tags = "[]";

    private UUID createdBy;

    private UUID updatedBy;

    private UUID publishedBy;

    private Instant createdAt = Instant.now();

    private Instant updatedAt = Instant.now();

    private Instant publishedAt;
}
