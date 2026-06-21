package com.onelake.quality.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record QualityRuleDTO(
    UUID id,
    String targetFqn,
    String targetColumn,
    String ruleType,
    String expression,
    String severity,
    UUID ownerId,
    String owner,
    Boolean enabled,
    Integer version,
    String schedule,
    BigDecimal lastPassRate,
    Instant createdAt
) {}
