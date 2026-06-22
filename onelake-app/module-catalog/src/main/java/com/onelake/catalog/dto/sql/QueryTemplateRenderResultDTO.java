package com.onelake.catalog.dto.sql;

public record QueryTemplateRenderResultDTO(
    String sql,
    int replacedCount,
    boolean submittedDirectly
) {}
