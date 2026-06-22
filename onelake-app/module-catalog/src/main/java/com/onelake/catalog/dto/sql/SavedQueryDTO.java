package com.onelake.catalog.dto.sql;

import java.time.Instant;
import java.util.UUID;

public record SavedQueryDTO(
    UUID id,
    String name,
    UUID ownerId,
    String owner,
    boolean shared,
    String sql,
    Instant updatedAt
) {}
