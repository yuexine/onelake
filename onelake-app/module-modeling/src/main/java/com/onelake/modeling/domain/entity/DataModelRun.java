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
@Table(name = "model_run", schema = "modeling")
@Getter
@Setter
public class DataModelRun {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID modelId;

    @Column(nullable = false, length = 16)
    private String status = "QUEUED";

    @Column(nullable = false, length = 32)
    private String triggerType = "MANUAL";

    private UUID sourceIntegrationRunId;
    private UUID orchestrationDagId;
    private String dagsterRunId;
    private String engineRunId;
    private String trinoQueryId;
    private String resourceGroup;
    private String computeProfile;
    private Instant queuedAt = Instant.now();
    private Instant startedAt;
    private Instant finishedAt;

    @Column(columnDefinition = "text")
    private String errorMsg;

    private Long rowsRead;
    private Long rowsWritten;
    private String artifactsPath;
    private Long estimatedScanBytes;
    private Long actualScanBytes;
    private BigDecimal costEstimate;
    private String queueReason;
    private Integer retryCount = 0;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
