package com.onelake.catalog.dto.sql;

import jakarta.validation.constraints.NotBlank;

public record SqlSaveQueryRequest(
    @NotBlank String name,
    @NotBlank String sql,
    boolean shared
) {}
