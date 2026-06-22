package com.onelake.catalog.dto.sql;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record QueryTemplateDTO(
    UUID id,
    String name,
    String category,
    String description,
    String sqlTemplate,
    List<PlaceholderSpec> placeholders,
    UUID ownerId,
    String ownerName,
    boolean shared,
    Instant updatedAt
) {
    public record PlaceholderSpec(
        String name,
        String type,
        boolean required,
        String defaultValue,
        String description
    ) {}
}
