package com.onelake.catalog.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AssetMaintenanceAssessmentDTO(
    UUID assetId,
    String fqn,
    String layer,
    String status,
    String freshnessStatus,
    Long freshnessLagMinutes,
    Integer freshnessSlaMinutes,
    Integer fileCount,
    Integer smallFileCount,
    Long totalBytes,
    Long smallFileThresholdBytes,
    List<String> risks,
    List<String> suggestedOperations,
    Instant lastSyncAt,
    Instant assessedAt
) {}
