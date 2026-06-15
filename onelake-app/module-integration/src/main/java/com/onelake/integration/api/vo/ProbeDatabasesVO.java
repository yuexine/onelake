package com.onelake.integration.api.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record ProbeDatabasesVO(
    @NotBlank String type,
    @NotNull Map<String, Object> config,
    String networkMode
) {}
