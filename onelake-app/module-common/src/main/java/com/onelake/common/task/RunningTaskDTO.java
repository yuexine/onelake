package com.onelake.common.task;

import java.time.Instant;
import java.util.UUID;

public record RunningTaskDTO(
    UUID id,
    String sourceModule,
    String taskType,
    String refType,
    String refId,
    String parentRefId,
    String title,
    String status,
    Integer progress,
    String phase,
    String detail,
    String errorCode,
    String errorMessage,
    String link,
    Boolean cancellable,
    String cancelEndpoint,
    Instant startedAt,
    Instant updatedAt,
    Instant finishedAt
) {}
