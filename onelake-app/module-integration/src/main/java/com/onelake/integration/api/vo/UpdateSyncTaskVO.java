package com.onelake.integration.api.vo;

public record UpdateSyncTaskVO(
    String name,
    String mode,
    String sourceTable,
    String targetTable,
    Object fieldMapping,
    String scheduleCron,
    Integer rateLimit,
    Integer dirtyThreshold,
    String airbyteConnectionId
) {}
