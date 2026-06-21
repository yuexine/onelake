package com.onelake.quality.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnTransformer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "run_result", schema = "quality")
@Getter @Setter
public class RunResult {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false) private UUID ruleId;
    private UUID jobRunId;
    @Column(nullable = false) private Boolean passed;
    private BigDecimal passRate;
    private Long failedRows = 0L;
    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String sample;
    private Instant checkedAt = Instant.now();
}
