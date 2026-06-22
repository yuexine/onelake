package com.onelake.catalog.dto.sql;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record QueryTemplateSaveRequest(
    @NotBlank String name,
    String category,
    String description,
    @NotBlank String sqlTemplate,
    List<QueryTemplateDTO.PlaceholderSpec> placeholders,
    boolean shared
) {}
