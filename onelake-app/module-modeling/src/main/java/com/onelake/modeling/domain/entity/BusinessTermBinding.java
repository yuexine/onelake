package com.onelake.modeling.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "business_term_binding", schema = "modeling")
@Getter
@Setter
public class BusinessTermBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID termId;

    private UUID assetId;

    @Column(nullable = false)
    private String assetFqn;

    private String columnName;

    @Column(nullable = false, length = 16)
    private String relationType = "DEFINES";

    @Column(nullable = false, length = 16)
    private String source = "MANUAL";

    private BigDecimal confidence;

    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    private UUID createdBy;

    private Instant createdAt = Instant.now();

    private Instant updatedAt = Instant.now();
}
