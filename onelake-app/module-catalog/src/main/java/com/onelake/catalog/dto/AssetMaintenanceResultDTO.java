package com.onelake.catalog.dto;

import java.time.Instant;
import java.util.UUID;

public record AssetMaintenanceResultDTO(
    UUID assetId,
    String fqn,
    String operation,
    String status,
    String statement,
    String message,
    Instant submittedAt
) {}
