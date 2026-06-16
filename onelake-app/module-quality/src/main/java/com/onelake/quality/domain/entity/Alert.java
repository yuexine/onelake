package com.onelake.quality.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity(name = "QualityAlert")
@Table(name = "alert", schema = "quality")
@Getter @Setter
public class Alert {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false) private UUID tenantId;
    private UUID ruleId;
    @Column(nullable = false, length = 16) private String level;   // INFO/WARN/CRITICAL
    @Column(nullable = false) private String message;
    @Column(nullable = false, length = 16) private String status = "OPEN";   // OPEN/ACK/CLOSED
    private Instant createdAt = Instant.now();
}
