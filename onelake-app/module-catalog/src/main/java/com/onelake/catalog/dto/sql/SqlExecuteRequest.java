package com.onelake.catalog.dto.sql;

import jakarta.validation.constraints.NotBlank;

public record SqlExecuteRequest(
    @NotBlank String sql,
    String engine,
    String resourceGroup
) {}
