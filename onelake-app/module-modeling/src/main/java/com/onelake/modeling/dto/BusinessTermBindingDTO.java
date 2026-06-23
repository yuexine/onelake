package com.onelake.modeling.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BusinessTermBindingDTO(
    UUID id,
    UUID termId,
    String termCode,
    String termName,
    UUID assetId,
    String assetFqn,
    String columnName,
    String relationType,
    String source,
    BigDecimal confidence,
    String status,
    Instant createdAt,
    Instant updatedAt
) {}
