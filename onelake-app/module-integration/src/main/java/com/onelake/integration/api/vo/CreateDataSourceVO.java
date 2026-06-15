package com.onelake.integration.api.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

public record CreateDataSourceVO(
    @NotBlank String name,
    @NotBlank String type,
    @NotNull Map<String, Object> config,
    String secretRef,
    String networkMode,
    String envLevel,
    UUID projectId
) {}
