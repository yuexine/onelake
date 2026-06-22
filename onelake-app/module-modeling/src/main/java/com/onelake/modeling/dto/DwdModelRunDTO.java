package com.onelake.modeling.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DwdModelRunDTO(
    UUID id,
    UUID modelId,
    String status,
    String triggerType,
    UUID sourceIntegrationRunId,
    UUID orchestrationDagId,
    String dagsterRunId,
    String engineRunId,
    String trinoQueryId,
    String resourceGroup,
    String computeProfile,
    Instant queuedAt,
    Instant startedAt,
    Instant finishedAt,
    String errorMsg,
    Long rowsRead,
    Long rowsWritten,
    String artifactsPath,
    Long estimatedScanBytes,
    Long actualScanBytes,
    BigDecimal costEstimate,
    String queueReason,
    Integer retryCount,
    Instant createdAt,
    Instant updatedAt
) {}
