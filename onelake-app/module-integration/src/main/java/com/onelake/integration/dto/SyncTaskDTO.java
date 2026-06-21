package com.onelake.integration.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SyncTaskDTO(
    UUID id,
    UUID sourceId,
    String sourceName,
    String name,
    String mode,
    String sourceTable,
    String targetTable,
    List<Map<String, Object>> fieldMapping,
    String scheduleCron,
    Integer rateLimit,
    Integer dirtyThreshold,
    String status,
    String airbyteConnectionId,
    Instant createdAt
) {}
