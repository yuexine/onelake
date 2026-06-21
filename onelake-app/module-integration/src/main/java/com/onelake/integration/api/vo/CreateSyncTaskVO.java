package com.onelake.integration.api.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateSyncTaskVO(
    @NotNull UUID sourceId,
    @NotBlank String name,
    @NotBlank String mode,
    @NotBlank String sourceTable,
    @NotBlank String targetTable,
    Object fieldMapping,
    String scheduleCron,
    Integer rateLimit,
    Integer dirtyThreshold,
    String airbyteConnectionId
) {}
