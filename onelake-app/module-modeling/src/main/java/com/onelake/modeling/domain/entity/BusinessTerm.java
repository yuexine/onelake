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
@Table(name = "business_term", schema = "modeling")
@Getter
@Setter
public class BusinessTerm {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    private UUID domainId;

    @Column(columnDefinition = "text")
    private String definition;

    @Column(columnDefinition = "text")
    private String caliberSql;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String synonyms = "[]";

    private UUID ownerId;

    private String ownerName;

    private UUID stewardId;

    @Column(nullable = false, length = 16)
    private String status = "DRAFT";

    @Column(nullable = false)
    private Integer version = 1;

    @Column(length = 8)
    private String sensitivityLevel;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String tags = "[]";

    private UUID createdBy;

    private UUID updatedBy;

    private UUID approvedBy;

    private Instant createdAt = Instant.now();

    private Instant updatedAt = Instant.now();

    private Instant approvedAt;
}
