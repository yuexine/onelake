package com.onelake.orchestration.dto;

import java.time.Instant;
import java.util.UUID;

public record JobRunDTO(
    UUID id,
    UUID dagId,
    String dagName,
    String dagsterJob,
    String dagsterRunId,
    String triggerType,
    String status,
    Instant startedAt,
    Instant finishedAt,
    UUID triggeredBy,
    String triggeredByName
) {}
