package com.onelake.modeling.dto;

import java.time.Instant;
import java.util.UUID;

public record BusinessTermVersionDTO(
    UUID id,
    UUID termId,
    Integer version,
    String snapshot,
    String changeReason,
    UUID changedBy,
    Instant createdAt
) {}
