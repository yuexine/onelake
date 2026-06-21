package com.onelake.quality.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record QualityAlertDTO(
    UUID id,
    UUID ruleId,
    String level,
    String source,
    String message,
    String status,
    Instant createdAt,
    String targetFqn,
    String targetColumn,
    String ruleType,
    String expression,
    BigDecimal passRate,
    Long failedRows,
    List<Map<String, Object>> sample
) {}
