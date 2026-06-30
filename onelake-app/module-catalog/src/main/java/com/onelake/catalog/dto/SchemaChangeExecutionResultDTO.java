package com.onelake.catalog.dto;

import java.time.Instant;
import java.util.UUID;

public record SchemaChangeExecutionResultDTO(
    UUID approvalId,
    String assetFqn,
    String changeType,
    String status,
    String statement,
    String message,
    Instant executedAt
) {}
