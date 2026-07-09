package com.onelake.orchestration.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * DAG 运行实例接口响应对象。
 */
public record JobRunDTO(
    UUID id,
    UUID dagId,
    String dagName,
    String dagsterJob,
    String dagsterRunId,
    String triggerType,
    String status,
    Instant logicalDate,
    Instant dataIntervalStart,
    Instant dataIntervalEnd,
    UUID backfillId,
    Instant startedAt,
    Instant finishedAt,
    UUID triggeredBy,
    String triggeredByName
) {}
