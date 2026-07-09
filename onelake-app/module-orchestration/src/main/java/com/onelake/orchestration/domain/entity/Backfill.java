package com.onelake.orchestration.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * 真回填批次持久化实体。
 */
@Entity
@Table(name = "backfill", schema = "orchestration")
@Getter
@Setter
public class Backfill {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID dagId;

    @Column(nullable = false)
    private Instant rangeStart;

    @Column(nullable = false)
    private Instant rangeEnd;

    @Column(nullable = false, length = 16)
    private String grain = "DAY";

    @Column(nullable = false, length = 16)
    private String status = "QUEUED";

    @Column(nullable = false)
    private Integer totalRuns = 0;

    @Column(nullable = false)
    private Integer succeededRuns = 0;

    @Column(nullable = false)
    private Integer failedRuns = 0;

    @Column(nullable = false)
    private Integer maxParallel = 1;

    private UUID createdBy;

    @Column(length = 128)
    private String createdByName;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
}
