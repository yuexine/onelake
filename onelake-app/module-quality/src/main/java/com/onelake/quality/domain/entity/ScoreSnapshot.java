package com.onelake.quality.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "score_snapshot", schema = "quality")
@Getter @Setter
public class ScoreSnapshot {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false) private UUID tenantId;
    @Column(nullable = false) private String targetFqn;
    @Column(nullable = false, length = 16) private String dimension;   // COMPLETE/UNIQUE/VALID/FRESH
    @Column(nullable = false) private BigDecimal score;
    @Column(nullable = false) private LocalDate statDate;
}
