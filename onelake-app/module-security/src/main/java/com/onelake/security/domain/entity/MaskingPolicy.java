package com.onelake.security.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "masking_policy", schema = "security")
@Getter @Setter
public class MaskingPolicy {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false) private UUID tenantId;
    @Column(nullable = false) private String targetFqn;
    @Column(length = 8) private String classification;        // L1/L2/L3/L4
    @Column(length = 64) private String roleScope;
    @Column(nullable = false, length = 16) private String strategy;   // MASK/HASH/NULLIFY/PARTIAL
    private Integer priority = 100;
    private Instant createdAt = Instant.now();
}
