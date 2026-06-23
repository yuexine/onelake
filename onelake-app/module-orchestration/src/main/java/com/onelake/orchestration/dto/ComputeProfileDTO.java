package com.onelake.orchestration.dto;

import java.time.Instant;
import java.util.UUID;

public record ComputeProfileDTO(
    UUID id,
    String code,
    String displayName,
    String engine,
    String status,
    Integer cpuCores,
    Integer memoryGb,
    Long maxScanBytes,
    Integer timeoutSeconds,
    Instant createdAt,
    Instant updatedAt
) {
}
