package com.onelake.integration.dto;

import java.time.Instant;
import java.util.UUID;

public record SyncTaskDTO(
    UUID id,
    UUID sourceId,
    String name,
    String mode,
    String targetTable,
    String scheduleCron,
    Integer rateLimit,
    String status,
    Instant createdAt
) {}
