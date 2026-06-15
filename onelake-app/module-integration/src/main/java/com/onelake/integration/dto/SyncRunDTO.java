package com.onelake.integration.dto;

import java.time.Instant;
import java.util.UUID;

public record SyncRunDTO(
    UUID id,
    UUID taskId,
    String externalJobId,
    String status,
    Long rowsRead,
    Long rowsWritten,
    String errorCode,
    String errorMsg,
    String checkpoint,
    Long durationMs,
    Long throughputRows,
    Instant startedAt,
    Instant finishedAt
) {}
