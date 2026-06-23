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

@Entity
@Table(name = "compute_profile", schema = "orchestration")
@Getter
@Setter
public class ComputeProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID resourceGroupId;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String displayName;

    @Column(nullable = false, length = 32)
    private String engine;

    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    private Integer cpuCores;
    private Integer memoryGb;
    private Long maxScanBytes;
    private Integer timeoutSeconds;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
