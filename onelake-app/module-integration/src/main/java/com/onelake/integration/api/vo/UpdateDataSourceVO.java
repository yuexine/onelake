package com.onelake.integration.api.vo;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;
import java.util.UUID;

public record UpdateDataSourceVO(
    String name,
    Map<String, Object> config,
    String secretRef,
    String networkMode,
    String envLevel,
    UUID projectId
) {}
