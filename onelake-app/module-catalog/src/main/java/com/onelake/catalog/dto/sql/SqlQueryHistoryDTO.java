package com.onelake.catalog.dto.sql;

import java.time.Instant;
import java.util.UUID;

public record SqlQueryHistoryDTO(
    UUID id,
    String runner,
    Instant at,
    String trinoQueryId,
    Long scanBytes,
    Long durationMs,
    boolean ok,
    String status,
    String sql,
    String error
) {}
